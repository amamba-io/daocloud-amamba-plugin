# DaoCloud Amamba Plugin

用于集中扩展 Jenkins 与 DaoCloud Amamba 的集成能力。当前提供工作空间环境变量自动注入；不依赖 `daocloud-oic-auth` 插件，不需要修改 amamba 或 Jenkinsfile。

## 安装与配置

要求 Jenkins 2.479.3+、Java 17+。使用 Maven 3.9.6+ 执行：

```sh
mvn verify
```

在 Jenkins **Manage Jenkins → Plugins → Advanced settings** 上传 `target/daocloud-amamba.hpi`，按提示重启。

1. 创建 **System** 范围的 **Secret text** 凭据，内容为平台访问令牌（不包含 `Bearer `）。令牌需要具有目标工作空间的读取权限；由管理员负责轮换。
2. 在 **Manage Jenkins → System → DaoCloud Amamba** 配置平台入口 URL、凭据 ID 和请求超时（默认 5 秒，范围 1–300 秒）。平台入口 URL 默认为集群内地址 `http://istio-ingressgateway.istio-system.svc.cluster.local`，运行在 DCE5 集群内的 controller（内置 Jenkins）开箱即用；集群外的 controller 需改为外部的 DCE5 入口 URL。支持入口路径前缀；不要填入具体 API 路径。
3. 启用 **Inject DCE5_WORKSPACE_NAME**。默认关闭。

也可以使用 Configuration as Code 插件配置：

```yaml
unclassified:
  daocloudAmamba:
    platformUrl: "https://dce.example.com"
    credentialsId: "dce-platform-token"
    requestTimeoutSeconds: 5
    workspaceEnvironment:
      enabled: true
```

凭据应通过 Jenkins Credentials 或 JCasC secret source 单独提供，不要将真实令牌提交到仓库。Controller 必须能访问平台，HTTPS 证书须受 JVM 信任；插件不跳过证书校验，不跟随重定向。

## 行为

amamba 将流水线放在以 workspace ID 命名的 Jenkins 顶层 Folder 下。插件从 Job 父级链获取这个 ID，调用：

```text
GET <platformUrl>/apis/ghippo.io/v1alpha1/workspaces/<id>
Authorization: Bearer <token>
```

读取响应 `workspace.alias`，原样注入 `DCE5_WORKSPACE_NAME`。普通流水线、多分支及手动、定时、Webhook、重放触发都使用同一逻辑。

- 每次构建第一次读取环境时查询一次，并将结果保存到构建 Action；同一构建后续读取及恢复使用快照，新构建获取最新 alias。
- 查询失败（含凭据缺失、401/403/404、超时、无效响应和空 alias）时，构建继续，输出一次警告，不注入变量，不使用 ID 兜底。
- 顶层 Job 和非数字顶层文件夹不注入。数字顶层 Folder 必须遵循 amamba 工作空间约定；同名非 amamba 文件夹无法自动区分。
- 使用 Jenkins 原有环境变量优先级；显式参数、`environment`、`withEnv` 仍可能覆盖该变量。它是元数据，不可用于权限判定。
- 令牌只供 controller 访问平台，不传给 agent，不写入构建日志或快照。
- 已有构建快照保持稳定；关闭功能阻止新的查询和注入快照创建，不清除已有快照。

## 验收流水线

在 amamba 工作空间中运行以下 Jenkinsfile，确认两处输出均为该工作空间 alias：

```groovy
node {
    echo "workspace=${env.DCE5_WORKSPACE_NAME}"
    sh 'printf "workspace=%s\\n" "$DCE5_WORKSPACE_NAME"'
}
```

修改工作空间 alias 后重新运行，确认新构建输出新值。再测试定时、Webhook、多分支和重放。将测试环境凭据临时设为无效值后，新构建应告警并继续执行。

## 开发结构

- `io.jenkins.plugins.daocloud.amamba`：全局配置和平台查询客户端。
- `io.jenkins.plugins.daocloud.amamba.environment`：工作空间环境扩展及构建快照。

新增能力使用独立功能模块及开关，复用公共平台配置。测试使用 Jenkins Test Harness 和本地模拟 HTTP API，无需真实平台凭据。

## GitHub CI 与发布

- **Build**：`main` 推送、Pull Request 或手动触发时，在 Java 17 和 21 上执行 `mvn clean verify`，包含测试、SpotBugs 和格式检查；上传 HPI、SHA-256 校验和及测试报告。
- **Release**：推送 `vX.Y.Z` 或 `vX.Y.Z-rcN` 标签后，将构建版本设置为对应版本（去掉 `v`，不带 `SNAPSHOT`），复用完整构建验证。两个 Java 版本均通过后，将 Java 17 构建的 HPI 和 `SHA256SUMS` 发布至 GitHub Releases；`-rcN` 自动标记为预发布。
- 可以手动运行 **Release** 并填写版本号来验证版本替换和打包流程；手动运行只生成 Actions artifacts，不创建 Release。
- 发布使用 GitHub 自动提供的 `GITHUB_TOKEN`，无需额外配置发布凭据；仅发布 job 具有 `contents: write` 权限。此流程发布 HPI 到 GitHub，不发布至 Jenkins Update Center 或 Maven 仓库。

正式发布示例（先确认 `main` 的 Build 已通过）：

```sh
git checkout main
git pull --ff-only
git tag -a v0.1.0 -m 'Release 0.1.0'
git push origin v0.1.0
```
