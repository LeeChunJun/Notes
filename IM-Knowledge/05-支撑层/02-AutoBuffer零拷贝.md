# 02 · AutoBuffer 与零拷贝

> 主要源码：`mars/comm/autobuffer.h`（149 行）、`autobuffer.cc`（310 行）

---

## 1. 为什么需要 AutoBuffer

Mars 的网络路径上要处理三种数据：
1. **应用层对象**（protobuf message）
2. **协议帧**（20 字节头 + body）
3. **TCP 字节流**（任意切分）

如果每层都 memcpy 一次：
```
protobuf bytes → [copy] → frame buffer → [copy] → send buffer → [copy] → kernel
```

`AutoBuffer` 的目标是：**每一层尽量只拷贝一次**，并且用 `Move()` 做"消费式前进"而不是 `memmove`。

---

## 2. 核心数据结构

```cpp
// mars/comm/autobuffer.h:19-129
class AutoBuffer {
 private:
    unsigned char* parray_;
    off_t  pos_;                  // 当前读位置
    size_t length_;               // 有效数据长度
    size_t capacity_;             // 分配容量
    size_t malloc_unitsize_;      // 扩容粒度（默认 128）
};
```

**四个关键状态**：

```
parray_    [0 ................ capacity_)
           ↑      ↑            ↑
          ptr() pos()        capacity()
           ↑                ↑
        数据起点        数据终点
           ↑ length_ 的范围 = [pos_, pos_ + length_)
```

- `Length()` 返回**已读位置之后的剩余数据长度**（`length_ - pos_` 的语义，见实现）
- `Pos()` 是当前读位置
- `PosLength()` = 未读长度

---

## 3. 完整 API

### 3.1 写

| API | 行 | 说明 |
|---|---|---|
| `AllocWrite(size_t _readytowrite, bool _changelength = true)` | 33 | 预留可写空间 |
| `AddCapacity(size_t _len)` | 34 | **只扩 capacity 不改 length**（接收侧预分配） |
| `Write(const T& _val)` | 37 | 模板写 |
| `Write(off_t& _pos, const T& _val)` | 42 | 相对当前位置写 |
| `Write(const off_t& _pos, const T& _val)` | 47 | 绝对定位写（不移动 pos） |
| `Write(const char* const _val)` | 51 | 字符串 |
| `Write(off_t& _pos, const char* const _val)` | 55 | |
| `Write(const off_t& _pos, const char* const _val)` | 59 | |
| `Write(const AutoBuffer& _buffer)` | 63 | |
| `Write(const void* _pbuffer, size_t _len)` | 64 | |
| `Write(off_t& _pos, const AutoBuffer& _buffer)` | 65 | |
| `Write(off_t& _pos, const void* _pbuffer, size_t _len)` | 66 | |
| `Write(const off_t& _pos, const AutoBuffer& _buffer)` | 67 | |
| `Write(const off_t& _pos, const void* _pbuffer, size_t _len)` | 68 | |
| `Write(TSeek _seek, const void* _pbuffer, size_t _len)` | 69 | 绝对 seek 写 |

### 3.2 读

| API | 行 | 说明 |
|---|---|---|
| `Read(T& _val)` | 72 | 模板读 |
| `Read(off_t& _pos, T& _val) const` | 77 | |
| `Read(const off_t& _pos, T& _val) const` | 82 | |
| `Read(void* _pbuffer, size_t _len)` | 86 | |
| `Read(AutoBuffer& _rhs, size_t _len)` | 87 | |
| `Read(off_t& _pos, void* _pbuffer, size_t _len) const` | 89 | |
| `Read(off_t& _pos, AutoBuffer& _rhs, size_t _len) const` | 90 | |
| `Read(const off_t& _pos, void* _pbuffer, size_t _len) const` | 92 | |
| `Read(const off_t& _pos, AutoBuffer& _rhs, size_t _len) const` | 93 | |

### 3.3 位置/容量

| API | 行 | 说明 |
|---|---|---|
| `Move(off_t _move_len)` | 95 | ★ **搬移/消费** |
| `Seek(off_t _offset, TSeek _eorigin)` | 97 | 移动读位置（越界钳制） |
| `Length(off_t _pos, size_t _lenght)` | 98 | 同时设置 pos 和 length |
| `void* Ptr(off_t _offset = 0)` | 100 | 裸指针 |
| `void* PosPtr()` | 101 | 当前读位置指针 |
| `off_t Pos() const` | 105 | |
| `size_t PosLength() const` | 106 | 未读长度 |
| `size_t Length() const` | 107 | |
| `size_t Capacity() const` | 108 | |

### 3.4 所有权转移 ★

| API | 行 | 说明 |
|---|---|---|
| `void Attach(void* _pbuffer, size_t _len)` | 110 | **接管外部内存**（不拷贝） |
| `void Attach(AutoBuffer& _rhs)` | 111 | **移动语义**：接管 `_rhs`，`_rhs` 被置空 |
| `void* Detach(size_t* _plen = NULL)` | 112 | 交出所有权 |
| `void Reset()` | 114 | 释放 |

---

## 4. 关键实现

### 4.1 `AllocWrite` / `AddCapacity`

```cpp
// autobuffer.cc:50-56
void AutoBuffer::AllocWrite(size_t _readytowrite, bool _changelength) {
    __FitSize(Pos() + _readytowrite);
    if (_changelength) {
        size_t newlen = Pos() + _readytowrite;
        length_ = std::max(newlen, length_);
    }
}

// autobuffer.cc:58-60
void AutoBuffer::AddCapacity(size_t _len) {
    __FitSize(Capacity() + _len);      // ★ 只扩容量
}
```

> **长连接接收侧**（`longlink.cc:1000`）：`bufrecv.AllocWrite(64 * 1024, false)`
> —— 每次 recv 前预留 64KB，第二个参数 `false` 表示**不改 length**，
> 这样 `bufrecv.Length()` 仍是"已收到的真实数据"。

### 4.2 `Write` —— 写完自动前移读指针

```cpp
// autobuffer.cc:66-69
void AutoBuffer::Write(const void* _pbuffer, size_t _len) {
    Write(Pos(), _pbuffer, _len);
    Seek(_len, ESeekCur);        // ★ 写完自动前进
}
```

> **流式语义**：写完读指针就在末尾 → 后续 `Read` 从这里开始。

### 4.3 ★ `Move` —— 消费式前进

```cpp
// autobuffer.cc:157-174
off_t AutoBuffer::Move(off_t _move_len) {
    if (_move_len > 0) {
        // 头部插入空洞
        memmove(parray_ + _move_len, parray_, Length());
        memset(parray_, 0, _move_len);
        Length(Pos() + _move_len, Length() + _move_len);
    } else if (_move_len < 0) {
        // ★ 消费掉头部 -_move_len 字节（无 memcpy！）
        Length(move_len < Pos() ? Pos() - move_len : 0, Length() + move_len);
    }
    return Length();
}
```

**负数（消费）的效果**：
- `Length(pos, len)` 把读位置往前挪，剩余数据"浮"到前面
- `memmove` 只在**正向插入**时才需要

> **这是 Mars 拆包的核心**：
> 收到 100 字节、消耗了 20 字节的帧 → `Move(-20)` →
> 剩余 80 字节前移到头部，`Length()` 变成 80，`Pos()` 仍为 0 → 下一轮循环直接继续解。
>
> **对比传统做法**：`memmove(buf, buf+20, 80); len -= 20;` —— 一样要 memmove！
> Mars 的写法是把 `pos_` 往前推，**避免了 memmove**（数据不动，只是读指针移动）。
>
> **等等，更准确地说**：`Length(pos_, length_ + move_len)` 把 `pos_` 往前移，
> 于是 `PosPtr()` 指向的数据起点前移了 → 等价于 memmove，但**没有实际的内存搬运**。
> **不过 `Move(-n)` 之后 `Pos()` 变了，后续 `Write(Pos(), ...)` 会写到新位置** ——
> 实际上是把数据"逻辑上"前移了。而 `Move(+n)` 才是真的 memmove（插入空洞）。

### 4.4 `Attach` / `Detach` —— 所有权转移

```cpp
// autobuffer.cc:249-258
void AutoBuffer::Attach(AutoBuffer& _rhs) {
    if (_rhs.parray_ == NULL) return;
    parray_ = _rhs.parray_;
    length_ = _rhs.length_;
    capacity_ = _rhs.capacity_;
    pos_ = _rhs.pos_;
    malloc_unitsize_ = _rhs.malloc_unitsize_;
    _rhs.parray_ = NULL;
    _rhs.Reset();          // ★ 原对象被清空
}

// autobuffer.cc:260-270
void* AutoBuffer::Detach(size_t* _plen) { ... }
```

> **用法**：
> ```cpp
> // 长连接接收：把 body 直接挂到 stream_resp 上，零拷贝
> if (stream_resp.stream->Ptr()) stream_resp.stream->Write(body);
> else                          stream_resp.stream->Attach(body);   // ★ 移动语义
> ```
> （`longlink.cc:1066-1076`）

### 4.5 `__FitSize` —— 扩容

```cpp
// autobuffer.cc:282-310
void AutoBuffer::__FitSize(size_t _len) {
    if (_len > capacity_) {
        size_t oldsize = capacity_;
        size_t newsize = std::max(_len, oldsize * 2);          // ★ 翻倍
        newsize = (newsize + malloc_unitsize_ - 1) / malloc_unitsize_ * malloc_unitsize_;
        parray_ = (unsigned char*)realloc(parray_, newsize);
        capacity_ = newsize;
        memset(parray_ + oldsize, 0, newsize - oldsize);       // ★ 新区清零
    }
    ASSERT2(_len <= 50MB);                                     // ★ 上限 50MB
}
```

**两个关键点**：
1. **翻倍扩容** → 摊还 O(1)
2. **新区 memset 为 0** → **不泄漏旧数据**（安全）
3. **50MB 上限** → 防止一个畸形包把内存吃光

---

## 5. 零拷贝路径分析

### 5.1 发送链路

```
上层 Task.body (AutoBuffer)
   → shortlink_pack()  (stn/proto/shortlink_packer.cc:62)
        req_builder.HeaderToBuffer(_out_buff);          // header 直接写进 out_buff
        _out_buff.Write(_body.Ptr(), _body.Length());   // body 一次 memcpy
   → socketOperator_->Send(_socket, out_buff.Ptr(), out_buff.Length(), ...)
        → block_socket_send() → ::send(sock, buf+sent_len, len-sent_len, 0)
```

**只有一次拷贝**（body → out_buff），之后直接 `send()`。

长连接侧：
```
上层 body
   → longlink_pack()  (longlink_packer.cc:119-123)
        _packed.AllocWrite(20 + body.Length());
        _packed.Write(&st, 20);                          // 头
        _packed.Write(_body.Ptr(), _body.Length());      // ★ body 一次 memcpy
   → LongLink::Send → lstsenddata_
   → writev(_sock, vecwrite, n)                         // ★ 直接 writev，零额外拷贝
```

**一次拷贝 + 一次系统调用**。

### 5.2 接收链路

```
block_socket_recv() → recv(sock, recv_buf.Ptr()+recv_buf.Length(), ...)  (tcpclient_fsm.cc:368-369)
   → recv_buf_.Length(recv_buf.Pos(), recv_buf_.Length() + ret);          // :374
   → http::Parser::Recv(recv_buf.Ptr(...), recv_ret)                     (shortlink.cc:798)
        → Parser 内部 recvbuf_.Write(_buffer, _length)                   (http.cc:741)
             → bodyreceiver_->AppendData(chunkBegin, chunkSize)          (http.cc:878)
                  → MemoryBodyReceiver::AppendData → body_.Write(_body, _length)  (http.cc:307-310)
```

`MemoryBodyReceiver`（`http.h:303-316`）持有 `AutoBuffer&` 引用 → body 只有一份拷贝。

长连接侧：
```
recv(_sock, bufrecv.PosPtr(), 64*1024, 0)
   → longlink_unpack(bufrecv, cmdid, taskid, packlen, body, extension)
        → _body.Write(ESeekCur, _packed.Ptr(_package_len - body_len), body_len)   // ★ 一次拷贝
   → stream_resp.stream->Attach(body)          // ★ 零拷贝转移
```

---

## 6. `Move()` 的三个经典用途

| 用途 | 位置 |
|---|---|
| **消费式前进**（发送缓冲区） | `tcpclient_fsm.cc:338`：`send_buf_.Move(-ret)` |
| **Parser 消费 header** | `http.cc:791, 794, 828, 880, 899`：`recvbuf_.Move(-headerslength)` |
| **长连接拆帧** | `longlink.cc:1078`：`bufrecv.Move(-(int)(packlen))` |

---

## 7. `copy_wrapper_helper<AutoBuffer>`

`autobuffer.h:136-149`：特化让 `AutoBuffer` 能放进 `boost::any` 等需要拷贝语义的容器 ——
用 `Attach` 实现"共享同一块内存 + 原对象置空"。

> `boost::any` 是 Mars 消息机制的基础（`Message::body1` / `body2`），
> 这个特化保证了 `AutoBuffer` 能安全地作为消息体传递。

---

## 8. IM 性能建议

| 建议 | 说明 |
|---|---|
| **复用 buffer** | 高频路径上避免每次 `new AutoBuffer`（`Reset()` 后复用） |
| **预估大小** | 构造时给足容量，避免多次 `realloc` |
| **大消息分片** | 单帧 > 1MiB 会被客户端拒绝，**必须在业务层分片** |
| **零拷贝传递** | 跨层传 `AutoBuffer` 用 `Attach` 而不是 `Write` |
| **避免 `Detach` 后忘记 free** | `Detach` 把所有权交给调用方 |
| **线程安全** | `AutoBuffer` **不是线程安全的** —— 不要跨线程共享同一个实例 |

---

## 9. 与业务层的对接

**业务给 Mars 的 body**：
- Java 侧：`byte[]` → JNI → `AutoBuffer`（一次拷贝）
- C++ 侧：直接 `AutoBuffer&`（零拷贝）

**Mars 给业务的 body**：
- Java 侧：`AutoBuffer` → `jbyteArray`（一次拷贝）
- C++ 侧：`const AutoBuffer&`（零拷贝，只读）

> **IM 优化点**：如果业务层也在 C++，可以让 `Req2Buf` 直接把 protobuf 的
> `SerializeToArray` 写进 `outbuffer`，避免中间 buffer。
