# GitHub Actions 流水线说明

本仓库为 `mendhak/gpslogger` 的 fork。这里有 4 个 workflow，前两个继承自上游、后两个是本 fork 新增的。

| 文件 | 来源 | 用途 |
| --- | --- | --- |
| `android.yml` | 上游 | 在 push / PR 时跑 `assembleDebugUnitTest` + `testDebugUnitTest`。仅校验编译和单测。 |
| `generate-release-apk.yml` | 上游 | 上游签名/发版流程，依赖上游私有 secrets（`GPG_SIGNING_*`、`KEYSTORE` 等）。在 fork 里默认不会通过，保留是为了减少与上游 master merge 的冲突。**fork 用户不要触发这个 workflow。** |
| `staticsite.yml` | 上游 | 上游静态站点构建。fork 不需要管。 |
| `fork-build-apk.yml` | 本 fork 新增 | fork 自动构建 APK。详见下文。 |

## `fork-build-apk.yml` 一句话说明

任意 push / PR 都会自动产出 **debug APK** 作为 workflow artifact，无需任何 secret；任意分支 push 还会额外创建一个独立的 GitHub prerelease；推 `v*` tag 时创建正式 GitHub Release；手动触发并选择 `build_release=true` 时也会创建一个独立 prerelease。

有签名 secrets 时，Release 里发布 `release-signed` APK；没有签名 secrets 时，Release 里发布 `debug-signed` APK。两者都可以被 Android 安装；旧版 `-unsigned.apk` 不能安装，不要下载。

### 触发方式

- 任意分支 `push` → 构建 debug APK artifact；有 keystore secrets 时发布 signed release APK，否则发布 debug-signed fallback APK；每次 push 都创建独立 prerelease
- `pull_request` → 只构建 debug APK artifact，不创建 Release
- 推 tag `v<...>` → 构建 debug APK artifact；有 keystore secrets 时发布 signed release APK，否则发布 debug-signed fallback APK，并创建正式 GitHub Release
- 手动 `workflow_dispatch` → 默认 debug artifact；选 `build_release=true` 时额外构建 signed release 或 debug-signed fallback APK，并创建独立 prerelease

### 产物在哪里看

- GitHub Releases 页面：直接下载 `.apk` 文件即可安装。不要下载 `.SHA256`，它只是校验文件。
- Actions 页面 → 对应 workflow run → 底部 **Artifacts**：GitHub 下载的是 zip 包，需要先解压，再安装里面的 `.apk`。
- 文件命名：
    - `gpslogger-travel-debug-<versionName>-<short_sha>.apk`：普通 debug artifact
    - `gpslogger-travel-release-<versionName>-<tag/push/manual...>.apk`：已配置 keystore secrets 时产出的 signed release APK
    - `gpslogger-travel-debug-<versionName>-<tag/push/manual...>.apk`：缺少 keystore secrets 时产出的 debug-signed fallback APK
    - 每个 build-release 产物附带 `<...>.SHA256` 校验文件

### 可选签名配置

如果希望产出真正的 release APK，需要在仓库 Settings → Secrets and variables → Actions 里配齐以下 4 个 secret：

| Secret name | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 你的 keystore 文件用 `base64 -w0 your.jks` 编码后的字符串 |
| `SIGNING_KEY_ALIAS` | keystore 里的 key alias |
| `SIGNING_KEY_PASSWORD` | key 密码 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |

只要其中任一缺失，workflow 会改为构建 debug-signed fallback APK，避免发布 Android 拒绝安装的 unsigned APK。

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

1. 日常测试包：push 到任意分支后，GitHub Actions 会创建一个独立 prerelease；没有配置 keystore secrets 时下载 `gpslogger-travel-debug-...apk`，配置后下载 `gpslogger-travel-release-...apk`。
2. 正式发版：本地 commit 完想发的代码后推 `v*` tag：
    ```bash
    git tag v135-travel.1
    git push origin v135-travel.1
    ```
3. 在 GitHub Releases 页面下载 APK 安装。Actions artifact 仍会保留一份 zip 包，用于调试和回溯。

### 安装排错

- 如果手机提示“软件包似乎无效”，先确认下载的不是旧 workflow 产出的 `-unsigned.apk`，也不是 Actions artifact 的 zip 包。
- 如果手机已安装过不同签名的 GPSLogger，Android 会拒绝覆盖安装；测试 debug-signed fallback 时先卸载旧版本再装。

### 注意

- 上游 `generate-release-apk.yml` 仍然存在，因为删它会在每次 merge upstream master 时产生冲突。如果你确定不会再 merge 上游，可以删掉。
- `fork-build-apk.yml` 文件名前缀刻意带 `fork-`，避免与上游未来新增的 workflow 冲突。
