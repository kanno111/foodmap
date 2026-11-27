# Java 发布上传工具

这是独立的 Java 21 Maven 模块，产物为 `target/foodmap-uploader.jar`。本地分片使用 JDK，Linux 发布路径通过 `ProcessBuilder` 调用 OpenSSH。

## 构建和试运行

Windows 在仓库根目录执行：

```powershell
./tools/Check-Project.ps1 -Task UploaderVerify
java -jar deploy/uploader/target/foodmap-uploader.jar --help
java -jar deploy/uploader/target/foodmap-uploader.jar split release.tar.gz deploy/uploader/target/example-parts
```

将 `release.tar.gz` 换成已有的本地发布包路径。输出目录应是父目录已经存在的新目录。每片最多 4 MiB，从 `release.part.0000` 起按四位编号递增，按文件名顺序拼接可还原原包。已有目录会被拒绝，分片读写失败时尝试清理本次创建的文件。

Linux 可进入本目录执行 `mvn verify`。测试使用临时文件、模拟传输和本机子进程。

## 发布流程

在 Actions 里运行 `java -jar release-tools/foodmap-uploader.jar publish .`。先校验本地发布包，再用现有部署账号和固定主机公钥创建独立上传目录。

优先通过 rsync 用当前版本的文件作为基准，同步已检查的 JAR、前端文件和版本标识。rsync 限时 10 分钟。服务器在本次暂存目录重建发布包，SHA-256 必须与 Actions 的原包完全一致，然后才上传部署元数据并激活。首次发布、rsync 不可用、同步超时或重建校验失败时，改走原有分片 SCP。

分片回退最多八路并发，传完一片即补位。单片限时 7 分钟，超时或明确的临时连接错误最多重试两次，认证失败和未知错误直接退出。分片上传阶段总预算 30 分钟，涵盖缓存检查、分片与元数据上传。

服务器在 `/opt/foodmap/incoming/.part-cache/<整包 SHA-256>/` 保存已校验的分片。分片先传入本次尝试的独立目录，校验后移入缓存。重跑时按分片哈希查找，只补传缺失或损坏的分片。日志中的 `Resuming release` 显示复用和待上传数量。打包时固定顺序和时间戳，同一批构建产物可以生成同一份包。缓存随服务器发布目录维护。

分片齐全后，服务器按编号重组并校验整包 SHA-256，再执行发布脚本。日志记录 rsync 传输字节数，或分片尝试与重组耗时，两条路径均记录应用激活耗时。分片重组校验限时 90 秒，激活限时 10 分钟。激活请求只调用一次，SSH 在此期间断开时先核对线上版本和服务状态。[工作流](../../.github/workflows/README.md) · [服务器发布脚本](../deploy.sh)
