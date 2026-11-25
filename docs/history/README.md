# 历史归档

这里保留各阶段的开发记录和验证依据，不代表当前版本的行为。查当前进展先看[开发记录](../development.md)，查功能与接口从[文档导航](../README.md)进入。

## 开发记录

- [第 04 册｜2026-11-03 至 2026-11-18](development/development-04.md)：审核发布、相册、搜索与榜单、社区及前后台体验。
- [第 03 册｜2026-10-29 至 2026-11-03](development/development-03.md)：点评发布、地图、照片、商户投稿和管理员审核。
- [第 02 册｜2026-10-27 至 2026-10-29](development/development-02.md)：部署、密码重置、餐厅、评价结构和前端。
- [第 01 册｜2026-10-27](development/development-01.md)：V2 工程基线、账号和基础认证。

## 搜索与监控

- [查询最终评测](search/elasticsearch-final-benchmark-2026-11-05.md)：v003＋查询熔断构建的准确度、串行耗时及适用边界。
- [检索告警演练](search/elasticsearch-alert-drill-2026-11-10.md)：停止 ES 时的告警触发与恢复。
- [检索故障演练](search/elasticsearch-metrics-fault-drill-2026-11-10.md)：ES 无响应暴露的超时问题、修复和复测。
- [同步指标验证](search/elasticsearch-sync-metrics-2026-11-10.md)：Outbox 同步指标、看板和告警规则验证。
- [同步唤醒与合并验证](search/elasticsearch-sync-wakeup-2026-11-10.md)：提交后唤醒、批内合并和定时兜底。
- [固定窗口与 Bulk 验证](search/elasticsearch-bulk-2026-11-10.md)：批量同步的自动验证与运行边界。

## 榜单与缓存

- [MySQL 读取基线](ranking/restaurant-ranking-read-2026-11-11.md)：不启用缓存时的快照读取和耗时。
- [Redis 阶段验证](ranking/restaurant-ranking-redis-2026-11-11.md)：该阶段的同进程对照、正确性与故障验证。
- [Caffeine 两级缓存验证](ranking/restaurant-ranking-caffeine-2026-11-11.md)：在同一 JVM 内对照三种模式，适合横向比较。不同报告的独立运行数据不要直接相除。

## 图片性能

- [图片读取延迟初测](performance/image-read-baseline-2026-11-24.md)：本机访问线上站点的首屏出图、看图交互和单张图片串行计时。
- [访客流程测速与首页启动优化](performance/user-flow-audit-2026-11-24.md)：线上访问、搜索、详情、榜单、地图与首页模块加载对照。
