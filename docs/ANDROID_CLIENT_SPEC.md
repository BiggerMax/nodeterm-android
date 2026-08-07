# nodeterm Android 客户端 — 实现规格（ANDROID_CLIENT_SPEC.md）

> 目标：为 [nodeterm](https://github.com/eneskirca/nodeterm)（node-based terminal manager，无限画布上的终端+AI agent 管理器）做一个原生 Android 伴生客户端（Kotlin + Jetpack Compose），对标官方 iOS "nodeterm mobile" 伴生 App。
> 协议/架构以主仓库源码为准（已核对 `src/main/remote/*`、`src/shared/rpc.ts`、`src/server/*`、`package.json`）。

---

## 1. 范围与里程碑

**决策（已确认）**：原生 Kotlin + Jetpack Compose；**分阶段 MVP**。

**iOS 伴生的能力集**（Android 对齐目标）：
1. 扫码配对（QR，E2EE 继 relay）
2. 实时观看 agent 工作、查看终端输出流
3. 应答 "NEEDS YOU" 提示
4. 往终端打字
5. 推送通知
6. 移动看板（board）视图

**里程碑**
- **P1（首批交付）**：扫码配对 + 持久的 E2EE relay 会话 + 项目/节点列表 + agent 状态流（RUNNING/NEEDS YOU 徽标、子 agent 卡片、上下文计量）+ 按行流式显示终端输出（不加载完整 VT 渲染）+ 应答 NEEDS YOU + 宿主侧推送通知入口。协议栈一次打通。
- **P2**：完整终端渲染（滚动回溯 + 输入）、移动看板、SSH/远端项目只读浏览。
- **P3**：文件/编辑器只读、dictation 入口、订阅能力打磨。

> 说明：P1 的终端"按行流式"渲染=在手机上行式显示宿主推送的文本，不做完整 ANSI/VT 状态机；P2 再引入 Termux `terminal-emulator` 之类的完整渲染器。

---

## 2. 总体架构

```
┌──────────────┐     E2EE (NaCl box)      ┌────────────┐     配对 token        ┌──────────┐
│ Android 端   │ ◄──────────────────────► │  中继 relay  │ ◄──────────────────► │  桌面HOST │
│ (client role)│   wss://relay?token=…    │ (不透密的中转)│    同一配对 token     │ (host role)│
└──────────────┘                          └────────────┘                        └──────────┘
```

- **Relay** 是"哑"字节中转：按配对 token 匹配 host↔client 连接，不透密、不参与握手。
- 所有业务流量都是 **HOST↔CLIENT 直连语义、物理上经 relay 转发**，端到端加密。
- Android 端始终是 **client role**；桌面 nodeterm 是 host role。

### 包结构（建议）
```
app/
  src/main/java/com/nodeterm/android/
    core/          # 纯 Kotlin 协议层
      e2ee/        #   NaCl box + HKDF（无 Android 依赖，可单测）
      framing/     #   终端帧 16B 头 + 编解码
      rpc/         #   RPC envelope + relay 状态机（可注入 transport）
      model/       #   Node/Agent/Project 数据模型（与主机 RPC 字段一致）
    net/           #   OkHttp WebSocket transport 适配层
    data/          #   会话持久化（配对 token、pubkey、信任列表）
    ui/            #   Compose：配对/列表/agent 流/终端行流/应答/设置
    notify/        #   FCM / 宿主推送入口
  build.gradle.kts 等
```

---

## 3. 协议契约（wire 常量，需逐字节对齐）

### 3.1 连接与配对
- relay WebSocket 地址：`wss://<relay>/?token=<urlencoded配对token>`（token 作为 **query param**，不再作为数据帧混在流量里）。
- client 必须**预先知道 host 的 NaCl box 公钥**（来自配对 QR 载荷）。
- 配对 QR 载荷/密钥文件：参考 `src/main/remote/pairing.ts`、`pairing-crypto.ts`、`key-file-codec.ts`（Android 端按同格式解析；若载荷格式字段不明确，以 host 侧 `key-file-codec.ts` 的实现为准，按键名逐字段一致）。

### 3.2 E2EE 原语（`src/main/remote/e2ee.ts`，纯函数）
- 算法：**NaCl box**（Curve25519 + XSalsa20-Poly1305），box 线格式 `nonce(24B) ‖ ciphertext ‖ mac`。
- 密钥：`baseKey = ECDH 预计算(nacl.box.before) = 稳定的每设备对密钥`（静态密钥，pin-once，**不能直接用于加密流量**）。
- 会话流量密钥：
  `sessionKey = HKDF-SHA256(baseKey, salt = hostNonce ‖ clientNonce, info = "nodeterm-relay-session-v2")` → **32B**。
  - RFC 5869；注意 **salt = hostNonce(先) ‖ clientNonce(后)**，双方一致；info 字符串是这个字面量。
  - 与 iOS CryptoKit `HKDF<SHA256>` 对齐。
- `encrypt(plain, shared) = nonce ‖ nacl.box.after(plain, nonce, shared)`；`decrypt` 校验 MAC 失败返回 null，不抛。
- **SAS**（Short Authentication String）：对 `baseKey` 做 SHA-512，取前 4 字节折叠成 32 位整数 `n`，`code = n % 1_000_000` 补齐 6 位，格式 `"NNN NNN"`。用于人肉 out-of-band 核对。

### 3.3 Relay 握手与状态机（`src/main/remote/relay-socket.ts`）
状态：`connecting → handshaking → ready → closed`。
1. client 收到 host pubkey（QR）后：`baseKey = deriveSharedKey(hostPubB64, ourSecret)`；发**明文 JSON text 帧** `e2ee_hello {publicKeyB64, nonceB64}`（nonce 为本端 16B 会话 nonce）。
2. host 学到 client pubkey，`baseKey = deriveSharedKey(clientPubB64, hostSecret)`；`sessionKey = deriveSessionKey(baseKey, hostNonce, clientNonce)`；回**明文 text 帧** `e2ee_ready {nonceB64}`（host 的 16B nonce）。
3. client：`sessionKey = deriveSessionKey(baseKey, hostNonce, clientNonce)`；发**加密** `{type:"e2ee_auth"}`（TAG_RPC=0x01 封装的 JSON）。
4. host 回**加密** `{type:"e2ee_authenticated"}`；双方 `state=ready`，触发 onReady。

**区别明文/密文**：WebSocket **text 帧** → 握手控制（明文 JSON）；**binary 帧** → 加密 box。中继保持 text/binary 语义透传。

**握手后所有 peer 消息 = E2EE box（binary）**，解密后明文布局：

```
[role:1B][seq:8B(LE)][tag:1B][payload…]
```
- **role**：本端=host→1 / client→2；只接受对端 role 的 tag——`PEER_ROLE`（client 端 =1），**拒绝反射回本端的 box**（防反射攻击）。
- **seq**：每方向严格递增的 8B（LE，u32 高位 + u32 低位），接收方拒绝 `seq <= recvSeq` 的消息——**防重放/乱序**；每次（重）连接重置为新流。
- **tag / payload**（解密后首字节）：
  - `0x01` TAG_RPC — JSON RPC envelope
  - `0x02` TAG_FRAME — 二进制终端帧（见 3.4 framing）
  - `0x03` TAG_TUNNEL_TEXT — 隧道内 Server-Edition rpc.ts 的 JSON RpcMessage
  - `0x04` TAG_TUNNEL_BIN — 隧道内 encodePtyData 二进制
- RPC envelope JSON（TAG_RPC）：`{kind: 'req', id, method, params}` / `{kind:'notify', method, params}` / `{kind:'res', id, ok, body}` / `{kind:'keepalive'}`。
  - 收到 `req` → 回调后按 id `respond`；收到 `notify` → 视为 id="" 的 req（勿应答）。
  - keepalive：**25s** 间隔发一个 encrypted `{kind:'keepalive'}`。
  - RPC 超时：**30s**；重连退避 `[.5,1,2,4,8,15]s`；**每次重连都由接管方签发新 token**（socket 本身不自行重拨，onClose 后由上层用新 token 重建）。

### 3.4 终端帧（`src/main/remote/framing.ts`，稳定 wire 常量）
16B 小端头 + payload：
```
[0]=kind=0x74  [1]=version=1  [2]=opcode  [3]=reserved(0)
[4..8)=streamId u32 LE
[8..12)=seq high u32 LE  [12..16)=seq low u32 LE
[16..)=payload
```
opcode（勿重排）：`Output=1, SnapshotStart=2, SnapshotChunk=3, SnapshotEnd=4, Resized=5, Error=6, Input=7, Resize=8, Subscribe=9, Unsubscribe=10, SnapshotRequest=11`。
- 背压阈值 `MAX_BINARY_BUFFERED_AMOUNT = 8MiB`；超阈值 sendFrame 返回 false（丢帧由 UI-sink 层 drop-and-redraw 兜底）。

### 3.5 隧道（3c/4c，represent 手机侧 watch/board 的关键通道）
- `sendTunnelText(json)`：把 **rpc.ts 协议**（见《Server Edition bridge》）的一条 JSON RpcMessage 塞进 E2EE box（tag 0x03）。文本=JSON RpcMessage；`0x04`=二进制 encodePtyData 帧。
- 隧道与普通流量同一条加密流、同一 sendSeq、FIFO。
- 这层承载手机端"看板视图 / 远端操作"等 Server-Edition 能力，P1 可不全部实现，但要留 `onTunnel` 分流点。

---

## 4. P1 功能与数据流（首批实现）

1. **配对（Pairing）**
   - 相机扫码 → 解析 host pubkey（+ relay url + 一次性配对 token）→ 签发并持久化 client 密钥对（`org.abstractj.tweetnacl` 或等价的 NaCl box），存 keystore。
   - 触发一次 relay 握手；host 侧 pin-once 批准；展示 SAS 6 位码供人肉核对。
2. **持久会话（Persistent session）**
   - 后台保持/按需重连：握手成功 → ready → 订阅节点/状态。
   - RPC 方法以 host 暴露为准（参考 `src/core/host-service.ts`、`canvas-sync.ts`、`src/server/handlers/index.ts` 的方法名/参数）。P1 至少打通：列出项目/节点、订阅 agent 状态事件、拉取/订阅终端输出流、应答 NEEDS YOU、发终端输入。
   - ⚠️ RPC 方法清单与参数字段在 P1 开工时需**以主机端对应 handler 的实现为准**逐字段核对，不要臆造方法名。
3. **Agent 状态流（watch）**
   - 展示节点：标题、类型、RUNNING/NEEDS YOU 徽标、子 agent 卡片、上下文计量（对齐 `agent-status-mirror.ts` 与 `hook-events.ts` 定义的状态模型）。
   - 事件驱动（hook-driven，非抓屏）。
4. **终端输出 = 按行流式**（P1）：订阅 streamId → 收 `Output`/`Snapshot*` 帧 → 解码 text → 附加到 Compose 滚动列表（行式）。不做 VT 状态机；P2 换完整渲染。
5. **应答 NEEDS YOU**：展示待批/待答项（对齐 `pending-approvals.ts`、`shared/remote/approval.ts`、`consent.ts`），可选同意/拒绝并回发。
6. **推送通知**：宿主把 RUNNING/NEEDS YOU 事件推给手机（对齐 `push-grants.ts`/`push-notify.ts` 的授权模型）；Android 端用 FCM 或宿主自建通道收 push → 本地通知。授权/型号以 host 侧 `push-grants.ts` 为准。

---

## 5. 依赖建议（最小集）
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- `com.squareup.okhttp3:okhttp`（WebSocket）
- 加密：`org.abstractj.tweetnacl:tweetnacl-java`（NaCl box，含 `curve25519`/`box`），HKDF-SHA256 用极简自实现（RFC5869 ~30 行）或 `com.google.crypto.tink:tink`（二选一，优先自写小函数避免重依赖）。
- Compose BOM + Activity Compose + Navigation Compose；CameraX 或 ZXing core 扫码。
- 后端 build：Gradle Kotlin DSL；`minSdk 26`、`targetSdk` 取当前稳定版。

> 遵循 YAGNI：不引完整终端引擎、不引 Monorepo 无关依赖。任何"可选"依赖都以"P1 需要才加"为准则。

---

## 6. 验证与验收（P1）
- `./gradlew :app:testDebugUnitTest` 覆盖：e2ee（生成密钥/会话key 与参考向量一致）、HKDF(自定义向量)、framing 编解码（roundtrip + 已知字节样例）、relay 状态机（注入 fake transport，走完整握手 ready）、RPC req/res/超时/keepalive。
- 若本机无 Android SDK/JDK，无法 `assembleDebug`-得输出时：如实上报，给出可编译所需的 SDK 版本/路径前提，不假装构建通过。
- 与真实 host 的一次联调：扫码→SAS 核对→状态流/终端输出上线，作为 P1 完成判据。

---

## 7. 参考文件（本地已 clone 到 `/tmp/nodeterm_clone`）
- `src/main/remote/relay-socket.ts` — 握手/RPC/帧状态机（最权威）
- `src/main/remote/e2ee.ts` — crypto 原语
- `src/main/remote/framing.ts` — 终端帧
- `src/shared/rpc.ts` — Server-Edition 隧道协议（RpcMessage）
- `src/main/remote/pairing*.ts` / `key-file-codec.ts` — 配对载荷
- `src/main/remote/relay-host.ts`、`relay-client.ts`、`relay-trust.ts`、`standing-host.ts` — 中继侧语义
- `src/core/agents/hooks/*`、`agent-status-mirror.ts`、`pending-approvals.ts` — agent 状态/应答模型
- `src/server/handlers/index.ts`、`ws.ts` — Server-Edition bridge 方法面
