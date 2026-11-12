# 01 · Mars 帧格式详解

> 主要源码：`mars/stn/proto/longlink_packer.h`（117 行）、`longlink_packer.cc`（189 行）

---

## 1. 唯一的帧头结构

`mars/stn/proto/longlink_packer.cc:36-44`：

```cpp
#pragma pack(push, 1)
struct __STNetMsgXpHeader {
    uint32_t head_length;      // 头部长度
    uint32_t client_version;   // 客户端版本号（版本校验位）
    uint32_t cmdid;            // 业务命令号
    uint32_t seq;              // 序列号（= Mars taskid）
    uint32_t body_length;      // body 长度
};
#pragma pack(pop)
```

`#pragma pack(push,1)` 保证 **无 padding** → 头长 = 5 × 4 = **20 字节**。

---

## 2. 字节布局表

| Offset | Size | Field | 字节序 | 语义 |
|---|---|---|---|---|
| 0 | 4 | `head_length` | **Big-endian** (`htonl`) | 整个头部长度。客户端恒为 **20**；解析端读出后可能是 >20（服务端扩展位） |
| 4 | 4 | `client_version` | Big-endian | 客户端版本号，**兼作版本协商/校验位**。sample 统一为 **200** |
| 8 | 4 | `cmdid` | Big-endian | 业务命令号 |
| 12 | 4 | `seq` | Big-endian | **= Mars taskid**，请求-响应配对键 |
| 16 | 4 | `body_length` | Big-endian | body 字节数 |
| 20 | N | `body` | — | 业务负载（IM 里通常是 protobuf） |

**整包长度 = `head_length + body_length`**（`longlink_packer.cc:84`）。

---

## 3. 打包实现

`longlink_packer.cc:106-126`：

```cpp
longlink_pack = [](uint32_t _cmdid, uint32_t _seq,
                   const AutoBuffer& _body, const AutoBuffer& _extension,
                   AutoBuffer& _packed, longlink_tracker* _tracker) {
    __STNetMsgXpHeader st = {0};
    st.head_length    = htonl(sizeof(__STNetMsgXpHeader));   // 20
    st.client_version = htonl(sg_client_version);
    st.cmdid          = htonl(_cmdid);
    st.seq            = htonl(_seq);
    st.body_length    = htonl(_body.Length());

    _packed.AllocWrite(sizeof(__STNetMsgXpHeader) + _body.Length());
    _packed.Write(&st, sizeof(st));

    if (NULL != _body.Ptr())
        _packed.Write(_body.Ptr(), _body.Length());

    _packed.Seek(0, AutoBuffer::ESeekStart);
};
```

**要点**：
1. **全部字段 `htonl` 转网络序（大端）**
2. `head_length` 固定 20（客户端不产生扩展头）
3. **`_extension` 参数被完全忽略** —— 默认编码器不承载 extension 通道（留给自定义编码器）
4. **无加密、无压缩、无 checksum**
5. 写完 `Seek(0, ESeekStart)` 复位读指针，方便后续 `PosPtr()` 直接 `writev`

---

## 4. 解包实现

`longlink_packer.cc:59-94`：

```cpp
static int __unpack_test(const void* _packed, size_t _packed_len,
                         uint32_t& _cmdid, uint32_t& _seq,
                         size_t& _package_len, size_t& _body_len) {
    __STNetMsgXpHeader st = {0};
    if (_packed_len < sizeof(__STNetMsgXpHeader)) {   // :66 头部不足
        _package_len = 0; _body_len = 0;
        return LONGLINK_UNPACK_CONTINUE;              // :69
    }

    memcpy(&st, _packed, sizeof(__STNetMsgXpHeader));  // :72

    uint32_t head_len       = ntohl(st.head_length);
    uint32_t client_version = ntohl(st.client_version);
    if (client_version != sg_client_version) {         // :76 ★ 版本不匹配 → 致命
        _package_len = 0; _body_len = 0;
        return LONGLINK_UNPACK_FALSE;                  // :79
    }
    _cmdid    = ntohl(st.cmdid);                       // :81
    _seq      = ntohl(st.seq);                         // :82
    _body_len = ntohl(st.body_length);                 // :83
    _package_len = head_len + _body_len;               // :84

    if (_package_len > 1024 * 1024)                    // :86 ★ 超过 1MiB
        return LONGLINK_UNPACK_FALSE;                  // :87
    if (_package_len > _packed_len)                    // :89 body 未收全
        return LONGLINK_UNPACK_CONTINUE;               // :90

    return LONGLINK_UNPACK_OK;                         // :93
}
```

```cpp
// longlink_packer.cc:128-144
longlink_unpack = [](const AutoBuffer& _packed, uint32_t& _cmdid, uint32_t& _seq,
                      size_t& _package_len, AutoBuffer& _body, AutoBuffer& _extension,
                      longlink_tracker* _tracker) {
    size_t body_len = 0;
    int ret = __unpack_test(_packed.Ptr(), _packed.Length(), _cmdid, _seq, _package_len, body_len);
    if (LONGLINK_UNPACK_OK != ret)
        return ret;
    _body.Write(AutoBuffer::ESeekCur, _packed.Ptr(_package_len - body_len), body_len);  // :141
    return ret;
};
```

### 4.1 三个返回值

| 常量 | 值 | 位置 | 语义 | 上层动作 |
|---|---|---|---|---|
| `LONGLINK_UNPACK_CONTINUE` | -2 | `longlink_packer.h:28` | 数据不足 | **保留缓冲区等更多 TCP 数据** |
| `LONGLINK_UNPACK_FALSE` | -1 | `longlink_packer.h:29` | 协议错误 | **必须断链**（`kEctNetMsgXP` + `kEctNetMsgXPHandleBufferErr(-10504)`） |
| `LONGLINK_UNPACK_OK` | 0 | `longlink_packer.h:30` | 一个完整帧 | 交给上层分发 |

> ⚠️ **注意 body 的偏移是 `_package_len - body_len`，即 `head_len`，不是硬编码 20**。
> 这意味着客户端**天然兼容变长头**（服务端可以在 20 字节后插入 options）。

---

## 5. 具体样例

客户端发 `cmdid = 3 (CMD_ID_SEND_MESSAGE)`、`taskid = 42`、`client_version = 200`、body 长 17：

```
00 00 00 14   head_length    = 20
00 00 00 C8   client_version = 200
00 00 00 03   cmdid          = 3
00 00 00 2A   seq            = 42
00 00 00 11   body_length    = 17
<17 bytes Chat.SendMessageRequest protobuf>
```

---

## 6. 关键常量一览

| 常量 | 值 | 位置 | 说明 |
|---|---|---|---|
| 头部长度 | `sizeof(__STNetMsgXpHeader)` = **20** | `longlink_packer.cc:37-43` | `#pragma pack(1)` 保证 |
| `sg_client_version` | 0（默认），sample 设 200 | `longlink_packer.cc:34` | `SetClientVersion()` 设置（`:55-57`） |
| 最大整包长度 | **1 MiB** | `longlink_packer.cc:86-88` | 超过 → `LONGLINK_UNPACK_FALSE` |
| `NOOP_CMDID` | **6** | `longlink_packer.cc:96` | 心跳 ping/pong 共用 |
| `SIGNALKEEP_CMDID` | **243** | `longlink_packer.cc:97` | UDP 信令保活 |
| `PUSH_DATA_TASKID` | **0** | `longlink_packer.cc:98` | 服务端主动 push 的 seq |
| `Task::kInvalidTaskID` | 0 | `mars/stn/stn.h:89` | |
| `Task::kNoopTaskID` | `0xFFFFFFFF` | `stn.h:90` | 心跳请求的 seq |
| `Task::kLongLinkIdentifyCheckerTaskID` | `0xFFFFFFFE` | `stn.h:91` | 信令校验请求的 seq |
| `Task::kSignallingKeeperTaskID` | `0xFFFFFFFD` | `stn.h:92` | TCP 路径信令包的 seq |
| `Task::kMinorLonglinkCmdMask` | `0xFF000000` | `stn.h:93` | 副长连 cmdid 高位掩码 |
| `kReservedTaskIDStart` | `0xFFFFFFF0` | `stn.h:44` | taskid 回绕上限 |
| `kEctNetMsgXP` | 6 | `stn.h:252` | 协议层错误类型 |
| `kEctNetMsgXPHandleBufferErr` | `-10504` | `stn.h:311` | 解包失败错误码 |

---

## 7. 头部扩展位（options）

Java 服务端 `NetMsgHeader.encode()` 支持在 5 个固定字段之后、body 之前插入一段 `options`，
并把 `head_length` 设为 `20 + options.length`：

```java
// samples/Server/server/src/main/java/com/tencent/mars/proxy/NetMsgHeader.java:111-127
final int headerLength = FIXED_HEADER_SKIP + (options == null ? 0 : options.length);
final int bodyLength = (body == null ? 0 : body.length);
...
dos.writeInt(headerLength);
dos.writeInt(CLIENTVERSION);
dos.writeInt(cmdId);
dos.writeInt(seq);
dos.writeInt(bodyLength);
if (options != null) { dos.write(options); }
```

C++ 解析端**确实兼容**（body 起点用 `head_len` 而非硬编码 20）。

> ⚠️ 但 Java 服务端的 `decode()`（`NetMsgHeader.java:69-84`）**只读 5 个 int 然后直接读 body，
> 没有跳过 options**。sample 里 `options` 从未被赋值，所以无害 ——
> 但说明该扩展位在 sample 中是半成品。

---

## 8. 传输层承载

长连接跑在 **TCP 或 TLS**（`LonglinkConfig::need_tls`，`stn.h:210`，默认 `true`）。

> **协议层本身不含加密**，机密性完全依赖 TLS。
> **开源版没有"消息级加密"** —— 如果 IM 需要端到端加密，
> 要自己在 `Req2Buf`/`Buf2Resp` 里做（业务层），或替换 `LongLinkEncoder`。

---

## 9. 客户端版本校验 —— 一个高频事故点

`longlink_packer.cc:76-80`：版本不匹配 → `LONGLINK_UNPACK_FALSE` → **断链**。

```
客户端 client_version != 服务端期望 → 每个包都解包失败 → 立刻断链 → 无限重连
```

**必须三处对齐**：
| 位置 | 值 |
|---|---|
| Android 客户端 | `StnLogic.setClientVersion(profile.productID())` → `MarsServiceNative.java:108`，sample 为 **200** |
| C++ 全局 | `SetClientVersion(uint32_t)`（`longlink_packer.cc:55-57`） |
| Java 服务端 | `NetMsgHeader.CLIENTVERSION = 200`（`NetMsgHeader.java:39`） |

> **IM 上线检查清单**：客户端发版改了 `client_version` 但服务端没同步 →
> **该版本的所有用户会完全无法建立长连接**。这是必须做灰度 + 双版本兼容的场景。

---

## 10. 如何扩展协议（加 magic / 加密 / 压缩）

Mars 的设计是**编码器插件化**。

`mars/stn/proto/longlink_packer.h:53-115`：

```cpp
class LongLinkEncoder {
 public:
    std::function<void(uint32_t _cmdid, uint32_t _seq,
                       const AutoBuffer& _body, const AutoBuffer& _extension,
                       AutoBuffer& _packed, longlink_tracker* _tracker)>
        longlink_pack;                                     // :64-70

    std::function<int(const AutoBuffer& _packed, uint32_t& _cmdid, uint32_t& _seq,
                      size_t& _package_len, AutoBuffer& _body, AutoBuffer& _extension,
                      longlink_tracker* _tracker)>
        longlink_unpack;                                   // :81-88

    std::function<uint32_t()> longlink_noop_cmdid;          // :91
    std::function<bool(uint32_t _taskid, uint32_t _cmdid, uint32_t _recv_seq,
                       const AutoBuffer& _body, const AutoBuffer& _extend)>
        longlink_noop_isresp;                              // :92-94
    std::function<uint32_t()> signal_keep_cmdid;            // :95
    std::function<void(AutoBuffer& _body, AutoBuffer& _extend)> longlink_noop_req_body;   // :96
    std::function<void(const AutoBuffer& _body, const AutoBuffer& _extend)> longlink_noop_resp_body; // :97
    std::function<uint32_t()> longlink_noop_interval;       // :98
    std::function<bool()> longlink_complexconnect_need_verify; // :99
    std::function<bool(uint32_t _cmdid, uint32_t _taskid,
                        const AutoBuffer& _body, const AutoBuffer& _extend)>
        longlink_ispush;                                   // :104-105
    std::function<bool(uint32_t _sent_seq, uint32_t _cmdid, uint32_t _recv_seq,
                        const AutoBuffer& _body, const AutoBuffer& _extend)>
        longlink_identify_isresp;                          // :106-111

    int packer_encoder_version;                            // :113
    void SetEncoderVersion(int _version);                   // :114
};
extern LongLinkEncoder gDefaultLongLinkEncoder;             // :117
```

**扩展步骤**：
1. 复制 `gDefaultLongLinkEncoder` 的 `longlink_pack` / `longlink_unpack`
2. 在头部前加 magic、加密位、压缩标志等
3. 通过 `LonglinkConfig::longlink_encoder`（`stn.h:202`）挂到指定长连
4. 或通过 `StnManager::SetDefaultLongLinkEncoder()`（`stn_manager.h:53`）设成全局默认

> **关键理解**：
> **Mars 本身不定义应用层协议，只提供"编码器插件"。**
> 这就是为什么开源版里找不到 `kMagicNumber` 之类的东西 ——
> 微信内部版有自己的 `LongLinkEncoder` 实现（带 magic + 加密 + 压缩协商），
> 但那是私有代码。

`longlink_tracker`（`longlink_packer.h:45-51`）是为打包器提供**跨包上下文**的钩子
（如分片状态、加密会话状态）。

---

## 11. `PackerEncoderVersion`

```cpp
// mars/stn/stn.h:48-51
enum PackerEncoderVersion {
    kOld = 1,
    kNew = 2,
};
```

```cpp
// longlink_packer.cc:100-103
int LongLinkEncoder::packer_encoder_version = PackerEncoderVersion::kOld;
void LongLinkEncoder::SetEncoderVersion(int _version) {
    packer_encoder_version = _version;
}
```

> **`kNew`（带 magic / 加密位 / 压缩协商的私有格式）只在接口层留了坑**：
> `LonglinkConfig::packer_encoder_version`（`stn.h:205`）、
> `net_core.cc:1308`（`GetPackerEncoderVersion()`）。
> **本仓库没有实现。**

---

## 12. 协议设计的三个亮点（面试可讲）

### 亮点 1：seq = taskid，零额外状态

不需要在连接上维护"期望的下一个 seq"计数器。
回包直接用 taskid 去任务表里 `find_if` 匹配 —— **天然支持乱序响应**。

### 亮点 2：seq = 0 表示 push

不需要额外的 flag 位，也不需要单独的 push 通道。
一个整数的特殊值就区分了"请求-响应"和"服务器推送"。

### 亮点 3：head_length 字段 = 可扩展头

用长度字段而不是固定偏移来定位 body，
未来加扩展位（options、加密标记）时**客户端老版本也能解析**（只要能跳过未知长度）。

---

## 13. 协议层的"反模式"提醒

| 反模式 | 后果 |
|---|---|
| 自定义 magic 但**服务端没同步** | 100% 握手失败，且表现为"连上了立刻断" |
| `client_version` 客户端改了服务端没改 | 同上 |
| body 超过 1MiB | 客户端直接 `LONGLINK_UNPACK_FALSE` 断链，服务端毫无察觉 |
| 忘记 `htonl` | 大小端机器间全乱（Android x86 与 ARM 混用时会踩） |
| 在 body 里塞明文敏感信息 | 开源版没有加密层，只有 TLS 保护 |
