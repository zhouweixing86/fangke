# Fangke Autopilot（定时任务 + 自动唤醒）

把现有功能拆成 **14 个互不依赖的任务**，每个任务可以单独定时执行"测试 → 优化 → 迭代"，
并在需要时**单独唤醒 AI 代理**（GitHub Copilot CLI）来修。

```
tasks.json          任务目录（周期、检查项、唤醒策略、给代理的提示词）
scanners.ps1        9 个确定性扫描器（不依赖数据库/凭据）
runner.ps1          执行器：挑任务 → 跑检查 → 写报告 → 按策略唤醒
register-schedule.ps1  注册 Windows 计划任务
allowlist.json      扫描器误报豁免（每条必须写理由）
```

产物（不提交，见 `.gitignore`）：

```
.autopilot/state.json        每个任务上次结果 / 下次到期 / 今日唤醒次数
.autopilot/runs/*.md         每次执行报告（检查输出 + 待执行提示词）
.autopilot/pending/*.prompt.md  已生成但未唤醒的提示词
.autopilot/logs/runner.log   运行日志
```

---

## 1. 任务划分

按现有模块切，尽量让每个任务**只看一个区域、跑一条最小验证命令**：

| ID | 任务 | 区域 | 周期 | 唤醒策略 | 检查方式 |
|---|---|---|---|---|---|
| T04 | 密钥与敏感数据扫描 | security | 1 天 | 失败才唤醒 | 扫描（规则 + 豁免清单） |
| T01 | 后端单测与构建基线 | server | 1 天 | 失败才唤醒 | `mvn test`（全量） |
| T06 | 设备适配层与能力矩阵回归 | device | 1 天 | 失败才唤醒 | `mvn -Dtest=Device*Test,...` |
| T02 | 前端构建（web + uniapp 三端） | web | 1 天 | 失败才唤醒 | `npm run build`（web）、`build:h5`/`build:mp-weixin`/`build:app`（uniapp） |
| T10 | 访问控制与越权检查 | security | 7 天 | 每周必唤醒 | 扫描（拦截器 + 白名单） |
| T03 | 数据库迁移与基线一致性 | db | 1 天 | 失败才唤醒 | 扫描（版本连续、建表统计） |
| T05 | 接口契约一致性 | api | 7 天 | 每周必唤醒 | 扫描（控制器 ↔ docs/11） |
| T07 | 审批流与授权下发回归 | approval | 7 天 | 每周必唤醒 | `mvn -Dtest=Approval*,Dispatch*,Guard*` |
| T08 | 动态二维码安全口径 | qr | 7 天 | 每周必唤醒 | 单测 + 明文码扫描 |
| T09 | 通知中心与定时任务健康度 | notify | 7 天 | 每周必唤醒 | 单测 + TODO 清点 |
| T11 | 报表与审计口径 | report | 7 天 | 每周必唤醒 | 单测（报表/账号/黑名单/异常映射） |
| T12 | 依赖与已知漏洞 | deps | 7 天 | 每周必唤醒 | 扫描（pom / package.json） |
| T14 | 文档与实现一致性 | docs | 7 天 | 每周必唤醒 | 扫描（用例数、页面数、版本号） |
| T13 | 性能与索引审计 | perf | 30 天 | 每月必唤醒 | 扫描（建表索引覆盖） |

> **唤醒策略**：`fail` = 只有检查失败（或 WARN）才唤醒；`always` = 到期就唤醒做一轮"优化迭代"。
> 每日唤醒总次数受 `tasks.json → defaults.maxWakesPerDay` 限制（默认 3），避免失控刷额度。
> 优先级（`priority`）越小越先跑，`auto` 模式按它排序。

---

## 2. 用法

```powershell
cd tools\autopilot

# 看哪些任务到期
.\runner.ps1 -Mode plan

# 只跑一个任务（出报告，不唤醒）
.\runner.ps1 -Mode run -Task T04

# 跑并唤醒代理（按该任务的 wakePolicy）
.\runner.ps1 -Mode run -Task T04 -Wake

# 强制唤醒 / 强制不唤醒（覆盖策略）
.\runner.ps1 -Mode run -Task T14 -Wake -WakePolicy always
.\runner.ps1 -Mode run -Task T14 -Wake -WakePolicy never

# 只生成提示词、不真的调代理（推荐先这样确认）
.\runner.ps1 -Mode run -Task T14 -Wake -WakePolicy always -DryRun

# 自动：跑到期的前 N 个（供计划任务调用）
.\runner.ps1 -Mode auto -MaxTasks 1 -Wake

# 看最近执行结果
.\runner.ps1 -Mode report
```

退出行为：有锁文件（`.autopilot/runner.lock`）时不会并发跑；锁超过 2 小时自动接管。

---

## 3. 每个任务怎么"单独唤醒"代理

1. runner 生成提示词 `.autopilot/pending/<时间>-<任务>.prompt.md`，内容 =
   **该任务的 prompt（tasks.json） + 本次检查结果 + 报告路径 + 通用约束**。
2. 按 `wakeArgs` 调用代理，默认：
   ```
   copilot -p "按文件 <prompt.md> 中的任务说明与检查结果执行修复与验证。报告在 <report.md> ..." --allow-all-tools --no-color
   ```
3. 代理的 stdout 会追加到报告的「代理执行」段，退出码也记在报告里。
4. 每个任务独立：A 任务唤醒不影响 B 任务的状态与下次到期时间。

> 代理命令来自 `tasks.json → defaults.wakeAgent`，所以换用别的 agent（如自建服务）只改这一处。
> 注意：`copilot` 需要已登录；`-Wake` 会消耗额度，默认**不**在计划任务里开启。

---

## 4. 注册成 Windows 计划任务

```powershell
# 每天 09:10 跑一个到期任务，只出报告（不唤醒）
.\register-schedule.ps1

# 每天 09:10 跑并允许按策略唤醒（会调 copilot，注意额度）
.\register-schedule.ps1 -EnableWake

# 定制时间/批量
.\register-schedule.ps1 -Time 08:30 -MaxTasks 2 -EnableWake

# 查看 / 卸载
.\register-schedule.ps1 -Action status
.\register-schedule.ps1 -Action remove
```

计划任务名默认 `FangkeAutopilot`，注册到当前用户，不需要管理员。

---

## 5. 9 个确定性扫描器（scanners.ps1）

| 扫描器 | 检查什么 | 结论规则 |
|---|---|---|
| `secret` | 明文口令/AK/SK/私钥；内网 IP 只作提示 | 有未豁免命中 → FAIL |
| `migration` | Flyway 版本连续、无重号、统计建表 | 重号/缺号 → FAIL |
| `endpoint` | 控制器端点 ↔ `docs/11` 覆盖 | 有未覆盖端点 → WARN |
| `authz` | 集中式 `/api/admin/**` 规则、App 公开白名单 | 无集中规则且控制器无校验 → WARN |
| `qr` | 生效迁移里是否只有 `code_hash`；仓储是否回退成"一次性消费" | 有隐患 → FAIL |
| `todo` | TODO/FIXME/待实现 数量与热点文件 | >40 处 → WARN |
| `deps` | Spring Boot / Java / 前端依赖版本 | 只报事实 |
| `index` | 建表是否有二级索引 | 有表只有主键 → WARN |
| `docprogress` | 用例数（优先读 surefire）、页面数、版本号 | 与 README 不一致 → WARN |

**误报处理**：只能通过 `allowlist.json` 豁免，且必须写 `reason`。禁止把敏感词改成拼音绕过。

---

## 6. 新增一个任务

1. 往 `tasks.json → tasks` 加一条：
   ```json
   {
     "id": "T15",
     "name": "考勤对接自检",
     "area": "attendance",
     "intervalDays": 7,
     "priority": 85,
     "wakePolicy": "always",
     "checks": [
       { "name": "相关单测", "type": "command", "cwd": "server", "run": "mvn-test" }
     ],
     "prompt": "你在【考勤对接】任务中被唤醒。目标：... 步骤：1) ... 5) ..."
   }
   ```
2. `check.run` 可用内置配方（`mvn-test`、`mvn-device-tests`、`mvn-approval-tests`、`mvn-qr-tests`、
   `mvn-notify-tests`、`mvn-report-tests`、`npm run build`）或任意命令字符串（走 powershell 执行）。
3. `.\runner.ps1 -Mode plan` 确认已识别，再 `-Mode run -Task T15`。

> 需要新扫描能力时，在 `scanners.ps1` 增加 `Invoke-XxxScan` 并在 `Invoke-Scanner` 里注册，
> 然后 `{"type":"scan","scanner":"xxx"}`。

---

## 7. 已知限制

| 限制 | 说明 |
|---|---|
| 需要真机/数据库的检查 | 扫描器都只读文件；E2E（设备、MySQL、MinIO）仍要人工或另设 CI |
| 唤醒会消耗额度 | 默认策略是"失败才唤醒"，`always` 只给周任务；每日上限 3 次 |
| Windows 专用 | 用了 `schtasks` 与 `.cmd` 包装；Linux 可把 `register-schedule.ps1` 换成 cron |
| 不做 git 操作 | runner 不改仓库、不提交；改动由被唤醒的代理完成 |
