# 公司行为驱动的行情复权与历史价格修正服务

基于 Spring Boot 3 / Java 17 / H2 的 HTTP 服务，统一登记拆股、现金分红、配股、合股（合并）
等公司行为，并按未复权 / 前复权 / 后复权三种口径查询历史价格与复权因子。

## 构建与运行

```bash
mvn test        # 运行全部自动化测试（22 个）
mvn package     # 构建可执行 jar
java -jar target/corporate-actions-java-1.0-SNAPSHOT.jar
```

默认使用进程当前目录下的 H2 文件库 `./data/corporate-actions`（见
`src/main/resources/application.properties`），测试使用独立的内存库，互不影响。
服务重启后自动从文件库恢复事件与原始价格，不会重复应用或丢失。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/prices/raw` | 录入/覆盖原始日线收盘价（同一标的同一日期为 upsert） |
| `POST` | `/api/events` | 登记公司行为事件 |
| `PUT`  | `/api/events/{eventId}` | 修正事件（不可改标的；改标的请先撤销再登记） |
| `DELETE` | `/api/events/{eventId}` | 撤销事件 |
| `GET`  | `/api/events?symbol=` | 列出某标的全部事件 |
| `GET`  | `/api/prices?symbol=&basis=` | 查询价格，`basis` 取 `RAW` / `FORWARD` / `BACKWARD` |

### 事件结构

```json
{
  "eventId": "evt-1",
  "symbol": "600000",
  "effectiveDate": "2024-01-03",
  "type": "SPLIT",
  "params": { "from": 1, "to": 2 }
}
```

每类事件携带类型化参数，参数非法直接拒绝登记（HTTP 400）：

| 类型 | `type` | `params` | 复权乘数 m（P = 生效日前一交易日原始收盘价） |
| --- | --- | --- | --- |
| 拆股 | `SPLIT` | `from`、`to`（正整数，且不相等） | `to / from` |
| 合股 | `MERGE` | `from`、`to`（正整数，且 `from > to`） | `from / to` |
| 现金分红 | `DIVIDEND` | `dividendPerShare`（正数，且小于 P） | `P / (P - D)` |
| 配股 | `RIGHTS` | `subscriptionPrice`（正数且小于 P）、`ratio`（每 1 股可配股数，正数） | `P / ((P + k·S) / (1 + k))` |

分红与配股的乘数依赖生效日前一交易日的原始收盘价：必须先录入该价格，否则登记/计算会被
拒绝并提示先录入事件前价格。

### 错误响应

统一格式 `{"code": "...", "message": "..."}`，可区分的错误码：

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 字段缺失、类型未知、参数非法、缺事件前价格 |
| 409 | `DUPLICATE_EVENT` | 同一 `eventId` 且业务内容完全相同，已登记过（幂等拒绝，不重复生效） |
| 409 | `CONFLICTING_EVENT` | 同一标的、同一生效日已有同类型事件；或 `eventId` 被不同内容占用 |
| 404 | `EVENT_NOT_FOUND` | 修正/撤销的事件不存在 |

同一标的同一生效日允许不同类型事件共存（例如同日拆股 + 分红），它们的乘数按
`(生效日期, 事件类型)` 的固定次序连乘；同类型同日事件视为冲突，必须先修正或撤销。

## 复权口径

记事件按生效日期升序（同日按类型固定次序）的复权乘数为 m₁, m₂, …：

- **后复权因子** `backwardFactor(t)`：所有生效日 ≤ t 的事件乘数连乘（保留 10 位小数，
  `HALF_UP`），最早日期为 1；`backward(t) = round(raw(t) × backwardFactor(t), 2)`。
- **前复权因子** `forwardFactor(t) = backwardFactor(t) / backwardFactor(最新日期)`，
  最新日期为 1；`forward(t) = round(raw(t) × forwardFactor(t), 2)`。
- 未复权 `rawFactor = 1`，价格即原始收盘价。

三种口径下恒有 `price = round(raw × factor, 2)`，价格最小单位 0.01；因子统一以
`BigDecimal` 10 位精度、`HALF_UP` 舍入累积，不因事件数量增长而扩大误差。

## 语义与一致性保证

- **乱序等价**：事件总是按生效日期（同日按事件类型）应用，与登记顺序无关；同一批事件
  任意顺序录入，复权结果逐位一致。
- **修正/撤销即整体重算**：所有查询都通过同一个纯函数式计算入口
  （`AdjustmentEngine.compute`）从事件序列整体重放，事件被修改或撤销后，历史价格与
  因子立即按新序列重算，结果与从零重放完全一致。
- **并发一致性**：每个标的一把读写锁——登记/修正/撤锁持写锁，查询持读锁，查询只能观察
  到某次事件应用前或应用后的完整状态，不会读到中间计算结果；写操作在事务内落盘。
- **重启恢复**：事件与原始价格持久化到 H2 文件库，启动即恢复；重复登记在服务层与
  数据库主键约束下双重防护。

## 自动化测试

- `AdjustmentEngineTest`：四类事件乘数、乱序登记逐位等价、三口径换算关系。
- `CorporateActionServiceIntegrationTest`：乱序、幂等、冲突、非法参数、修正/撤销重放等价。
- `HttpApiIntegrationTest`：HTTP 状态码与可区分错误码、三口径 JSON 输出。
- `ConcurrencyIntegrationTest`：16 线程并发重复登记只生效一次；读线程在并发修正时只能
  观察到修正前或修正后的完整因子。
- `RestartRecoveryIntegrationTest`：使用临时文件库真实重启两个 Spring 上下文，验证事件、
  价格与修正结果完整恢复。
