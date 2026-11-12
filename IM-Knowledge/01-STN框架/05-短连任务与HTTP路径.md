# 05 · 短连任务与 HTTP 路径

> 主要源码：`mars/stn/src/shortlink.h/.cc`、`shortlink_task_manager.h/.cc`、
> `mars/stn/proto/shortlink_packer.cc`、`mars/comm/http.h/.cc`

---

## 1. 短连在 IM 中的定位

| 用途 | 例子 |
|---|---|
| 拉取历史消息 | `GET /cgi/chat/history` |
| 拉取会话列表 | `GET /cgi/chat/convlist` |
| 上传大文件 / 图片 | `POST /cgi/upload` |
| 配置下发 | `GET /cgi/config` |
| 长连不可用时的降级路径 | 同一份 CGI，走 HTTP |

**为什么需要短连**：
1. **可缓存、可重试、可 CDN 化**（历史消息、图片）
2. **长连只承载"必须实时"的东西**，避免长连被大批量低优先级请求打爆
3. **降级**：长连挂掉时，至少消息还能发出去（只是慢）

---

## 2. `ShortLink` 类

`mars/stn/src/shortlink.h:50-57`：

```cpp
class ShortLink {
 public:
    ShortLink(const Task& _task, ...);
    ...
 private:
    Task task_;                     // ★ 每个 ShortLink 实例持有自己的 Task
    std::string url_;
    ...
};
```

**关键设计**：
> **短连没有线上关联键。** 响应与请求的绑定靠 **TCP 连接对象本身**：
> 每个 Task 创建一个 `ShortLink` 实例，响应通过这个实例的回调直接回到该 Task。

对比长连：

| | 长连接 | 短连接 |
|---|---|---|
| 线上格式 | Mars 私有 20B 头 + body | **标准 HTTP/1.1 POST** |
| 请求-响应关联键 | 帧头 `seq` = taskid | **无线上关联键**，靠连接对象绑定 |
| cmdid | 有 | 无 |
| 生命周期 | 长连接常驻 | 一次一连接（`Connection: close`），支持 keep-alive 复用 |

---

## 3. 请求构造 `shortlink_pack`

`mars/stn/proto/shortlink_packer.cc:32-64`：

```cpp
shortlink_pack = [](const std::string& _url,
                    const std::map<std::string, std::string>& _headers,
                    const AutoBuffer& _body,
                    const AutoBuffer& _extension,
                    AutoBuffer& _out_buff,
                    shortlink_tracker* _tracker) {
    Builder req_builder(kRequest);
    req_builder.Request().Method(RequestLine::kPost);       // :44
    req_builder.Request().Version(kVersion_1_1);             // :45

    req_builder.Fields().HeaderFiled(HeaderFields::MakeAcceptAll());             // :47
    req_builder.Fields().HeaderFiled(HeaderFields::MakeUserAgentMicroMessage()); // :48
    req_builder.Fields().HeaderFiled(HeaderFields::MakeCacheControlNoCache());   // :49
    req_builder.Fields().HeaderFiled(HeaderFields::MakeContentTypeOctetStream());// :50
    req_builder.Fields().HeaderFiled(HeaderFields::MakeConnectionClose());       // :51

    char len_str[32] = {0};
    snprintf(len_str, sizeof(len_str), "%u", (unsigned int)_body.Length());       // :54
    req_builder.Fields().HeaderFiled(HeaderFields::KStringContentLength, len_str);// :55

    for (auto iter = _headers.begin(); iter != _headers.end(); ++iter) {
        req_builder.Fields().HeaderFiled(iter->first.c_str(), iter->second.c_str()); // :58
    }

    req_builder.Request().Url(_url);                          // :61
    req_builder.HeaderToBuffer(_out_buff);                    // :62
    _out_buff.Write(_body.Ptr(), _body.Length());             // :63
};
```

### 3.1 固定请求头

| Header | 值 | 常量位置 |
|---|---|---|
| `Accept` | `*/*` | `http.cc:307` |
| `User-Agent` | `MicroMessenger Client` | `http.cc:298` |
| `Cache-Control` | `no-cache` | `http.cc:310` |
| `Content-Type` | `application/octet-stream` | `http.cc:311` |
| `Connection` | `close` | `http.cc:306` |
| `Content-Length` | body 长度（十进制） | `shortlink_packer.cc:53-55` |
| `Host` | 域名 | `shortlink.cc:611/620` |
| `X-Online-Host` | 同 Host（Windows 下为 IP 替换） | `shortlink.cc:612` |

> **`User-Agent: MicroMessenger Client`** —— 这是微信客户端的标识。
> 服务端可以据此做灰度、兼容、统计。

### 3.2 实际发出的报文

```
POST /mars/sendmessage HTTP/1.1\r\n
Accept: */*\r\n
User-Agent: MicroMessenger Client\r\n
Cache-Control: no-cache\r\n
Content-Type: application/octet-stream\r\n
Connection: close\r\n
Content-Length: 42\r\n
Host: marsopen.cn\r\n
X-Online-Host: marsopen.cn\r\n
\r\n
<42 bytes protobuf body>
```

### 3.3 URL / Host 组装

`mars/stn/src/shortlink.cc:599-621`：

```cpp
std::string url;
std::map<std::string, std::string> headers;
#ifdef WIN32
    std::string replace_host = _conn_profile.host;
    if (kIPSourceProxy == _conn_profile.ip_type) {
        url += "http://"; url += _conn_profile.host;
    } else {
        replace_host = _conn_profile.ip.empty() ? _conn_profile.host : _conn_profile.ip;
    }
    url += task_.cgi;
    headers[http::HeaderFields::KStringHost] = replace_host;
    headers["X-Online-Host"] = replace_host;
#else
    if (kIPSourceProxy == _conn_profile.ip_type) {
        url += "http://"; url += _conn_profile.host;
    }
    url += task_.cgi;
    headers[http::HeaderFields::KStringHost] = _conn_profile.host;
#endif
```

> **注意**：URL 只有 path（`task_.cgi`），**没有 scheme 和 host** ——
> 因为实际连接的是解析后的 IP，Host 头单独设置。这是标准的"IP 直连 + Host 头"做法，
> 免去 SNI 与证书域名匹配的问题（TLS 时 `need_tls` + `tls_group` 处理）。

---

## 4. 响应解析

`mars/stn/src/shortlink.cc:703-853`：

```cpp
AutoBuffer body;
AutoBuffer recv_buf;
AutoBuffer extension;
int status_code = -1;
off_t recv_pos = 0;
MemoryBodyReceiver* receiver = new MemoryBodyReceiver(body);
http::Parser parser(receiver, true);
...
Parser::TRecvStatus parse_status = parser.Recv(recv_buf.Ptr(recv_buf.Length() - recv_ret), recv_ret);
if (parser.FirstLineReady()) {
    status_code = parser.Status().StatusCode();     // :800
}
...
} else if (parse_status == http::Parser::kEnd) {     // :827
    if (is_keep_alive_) { ... }                      // :828-840
    if (status_code != 200) {                        // :842
        xerror2(TSF "@%0, status_code != 200, code:%1, http dump:%2 ...", ...);
        __RunResponseError(kEctHttp, status_code, _conn_profile, true);   // :849
    } else {
        __OnResponse(kEctOK, status_code, body, extension, _conn_profile, true);  // :852
    }
    break;
}
```

**`status_code != 200` 直接判失败**。IM 服务端如果要返回业务错误码，
应该走 **HTTP 200 + protobuf body 里带 errcode**（这也是 sample 的做法）。

---

## 5. HTTP 解析器（`mars/comm/http.h/.cc`）

```
http::
  struct less                              header map 忽略大小写
  enum THttpVersion { kVersion_0_9..kVersion_Unknow }   :36-42
  enum TCsMode { kRequest, kRespond }      :52-55
  class RequestLine                        :57   Method / Version / Url
  class StatusLine                         :99   Version / StatusCode / ReasonPhrase
  class HeaderFields                       :125  header map + Make* 工厂
  class IBlockBodyProvider                 :189
  class BufferBodyProvider                 :198
  class IStreamBodyProvider                :229
  class Builder                            :244  请求/响应序列化
  class BodyReceiver                       :285  抽象 body 接收器
  class MemoryBodyReceiver : BodyReceiver :303  ★ 持有 AutoBuffer&
  class Parser                             :318  ★ 增量解析器
class URLFactory                            :389  cgi?k=v&k=v
class StringBody : http::BodyReceiver      :414
```

### 5.1 `Parser` 的增量解析

`http::Parser::Recv(buffer, len)` 是**增量**的 —— 可以喂给它任意切分的 TCP 数据块。
它内部维护 `recvbuf_`（`AutoBuffer`），解析完 header 后用 `Move(-n)` 消费掉：

```cpp
// http.cc:791, 794, 828, 880, 899
recvbuf_.Move(-headerslength);
```

> **这就是为什么短连能正确处理 TCP 粘包/拆包** —— 靠 `AutoBuffer::Move` 的"消费式前进"，
> 不需要 memcpy。

### 5.2 `MemoryBodyReceiver` —— 零拷贝 body

`http.h:303-316`：
```cpp
class MemoryBodyReceiver : public BodyReceiver {
 public:
    explicit MemoryBodyReceiver(AutoBuffer& _body) : body_(_body) {}
    virtual void AppendData(const char* _body, size_t _length) {
        body_.Write(_body, _length);      // 直接写入外部 AutoBuffer
    }
 private:
    AutoBuffer& body_;
};
```

body 最终只有**一份拷贝**。

---

## 6. `ShortLinkTaskManager`

`mars/stn/src/shortlink_task_manager.h/.cc`。

与长连管理器结构类似（`lst_cmd_` + 1s tick），但有几个显著差异：

### 6.1 每个任务一个 `ShortLink` 实例

```cpp
// shortlink_task_manager.cc:685
running_id = (ShortLinkInterface*)worker;
```

`running_id` 存的是 **`ShortLinkInterface*` 指针**，而不是长连那种的整数 id。
这是因为短连的"在途状态"就是那个 socket 对象本身。

### 6.2 短连的 client_sequence_id 生成时机

`mars/stn/src/shortlink.cc:1160-1163`：
```cpp
// client_sequence_id 在 buf2resp 这里生成，防止重试 sequence_id 一样
unsigned short client_sequence_id = context_->GetManager<StnManager>()->GenSequenceId();
```

与长连一致（都在**首次编码前**生成，重试不重新生成）。

### 6.3 短连的额外超时

| 超时 | 错误码 |
|---|---|
| 首包超时 | `kEctHttpFirstPkgTimeout(-500)` |
| 包间超时 | `kEctHttpPkgPkgTimeout(-501)` |
| 读写超时 | `kEctHttpReadWriteTimeout(-502)` |
| **长轮询超时** | `kEctHttpLongPollingTimeout(-503)` |

长轮询是短连特有的（`Task::long_polling` + `Task::long_polling_timeout`）。
**IM 场景**：长轮询可以替代 WebSocket 做"轻量推送"（在没有长连能力的场景）。

### 6.4 `shortlink_task_manager.cc:1052-1062, 1263-1265`

会话超时时除了重试当前任务，还会**触发全部任务重做** + 重新鉴权。

---

## 7. 短连的连接建立

`mars/stn/src/tcp_socket_operator.cc:60-68`：

```cpp
ComplexConnect com_connect(kShortlinkConnTimeout /*10s*/, kShortlinkConnInterval /*2.5s*/);
// 或带 v4/v6 独立超时的变体
```

与长连共用 `ComplexConnect`（多 IP 竞速），详见 `05-支撑层/01-comm-socket多IP竞速.md`。

---

## 8. 短连 vs 长连 的选择决策树

```
                        ┌─ 是"必须实时到达"的消息吗？
                        │
              ┌─────────┴─────────┐
             是                   否
              │                   │
        ┌─────┴─────┐      ┌──────┴──────┐
        │ 长连接      │      │ 短连接       │
        │ kChannelLong│     │ kChannelShort│
        └───────────┘      └─────────────┘
              │
        ┌─────┴─────┐
        │ 长连可用吗？│
        └─────┬─────┘
              │
       不可用 → 任务留在队列等（不降级！）
```

> **Mars 的一个重要设计**：`kChannelLong`（纯长连）任务在长连不可用时
> **会一直排队等待**（`longlink_task_manager.cc:535-546`），
> 而不是降级到短连。这保证了 IM 消息的**送达语义**（最终会发出去，不会丢失）。

---

## 9. IM 中的短连优化建议

| 优化点 | 做法 |
|---|---|
| 减少建连开销 | 短连是"一次一连"，高频调用会耗尽端口；用 `keep-alive` 复用（`is_keep_alive_`） |
| 大文件上传 | 走短连 + 分片；长连带宽被占用会影响消息延迟 |
| 减少 DNS | 短连 host 列表要带多个 IP 兜底；`SetBackupIPs` |
| 区分错误 | HTTP 层错误（4xx/5xx）→ `kEctHttp`；业务错误 → 200 + body.errcode |
| 超时 | 用 `total_timeout` 而不是依赖默认值（40s 偏长） |
| 优先级 | 历史消息拉取优先级要低于消息发送 |
| 限流 | `limit_flow = true` 防止短连占满带宽 |
