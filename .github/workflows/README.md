# GitHub Actions 流水线说明

本仓库为 `mendhak/gpslogger` 的 fork。这里有 4 个 workflow，前两个继承自上游、后两个是本 fork 新增的。

| 文件 | 来源 | 用途 |
| --- | --- | --- |
| `android.yml` | 上游 | 在 push / PR 时跑 `assembleDebugUnitTest` + `testDebugUnitTest`。仅校验编译和单测。 |
| `generate-release-apk.yml` | 上游 | 上游签名/发版流程，依赖上游私有 secrets（`GPG_SIGNING_*`、`KEYSTORE` 等）。在 fork 里默认不会通过，保留是为了减少与上游 master merge 的冲突。**fork 用户不要触发这个 workflow。** |
| `staticsite.yml` | 上游 | 上游静态站点构建。fork 不需要管。 |
| `fork-build-apk.yml` | 本 fork 新增 | fork 自动构建 APK。详见下文。 |

## `fork-build-apk.yml` 一句话说明

任意 push / PR 都会自动产出 **debug APK** 作为 workflow artifact，无需任何 secret；推到 `master` 时额外刷新 `nightly` 预发布 Release；推 `v*` tag 时创建正式 GitHub Release。

### 触发方式

- 任何非 `master` 分支 `push` 或 `pull_request` → 只构建 debug APK
- 推到 `master` → 同时构建 debug + release APK，并刷新固定的 `nightly` 预发布 Release
- 推 tag `v<...>` → 同时构建 debug + release APK，并创建正式 Release
- 手动 `workflow_dispatch` → 默认 debug；选 `build_release=true` 时同时构建 release（不发 Release）

### 产物在哪里看

- 进 Actions 页面 → 点对应的 workflow run → 滚到底部的 **Artifacts** 区域
- 文件命名：
    - `gpslogger-travel-debug-<versionName>-<short_sha>.apk`
    - `gpslogger-travel-release-<versionName>-<tag>[-unsigned].apk`（unsigned 后缀只在没配签名 secrets 时出现）
    - 每个 release artifact 附带 `<...>.SHA256` 校验文件

### 可选签名配置

如果希望 release APK 被签名（otherwise 装机会被 Android 拒绝），需要在仓库 Settings → Secrets and variables → Actions 里配齐以下 4 个 secret：

| Secret name | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 你的 keystore 文件用 `base64 -w0 your.jks` 编码后的字符串 |
| `SIGNING_KEY_ALIAS` | keystore 里的 key alias |
| `SIGNING_KEY_PASSWORD` | key 密码 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |

只要其中任一缺失，release APK 会以 `-unsigned` 后缀产出，构建不会失败。

### 生成 keystore 的命令（一次性）

```bash
keytool -genkeypair -v \
  -keystore your.jks -storetype JKS \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias your_alias

# 复制到 secret 里：
base64 -w0 your.jks
```

### 发版步骤

1. 日常测试包：push 到 `master` 后，GitHub Actions 会刷新 `nightly` 预发布 Release。
2. 正式发版：本地 commit 完想发的代码后推 `v*` tag：
    ```bash
    git tag v135-travel.1
    git push origin v135-travel.1
    ```
3. 在 GitHub Releases 页面下载 APK 安装。

### 注意

- 上游 `generate-release-apk.yml` 仍然存在，因为删它会在每次 merge upstream master 时产生冲突。如果你确定不会再 merge 上游，可以删掉。
- `fork-build-apk.yml` 文件名前缀刻意带 `fork-`，避免与上游未来新增的 workflow 冲突。
