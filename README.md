# lykke

一个把情侣纪念网站打包成安卓 App 的 **WebView 壳**。App 打开后直接加载现有网站，**网站内容、数据、功能完全不变**；以后网页更新，App 里自动同步。

- 应用名：lykke
- 包名：com.lykke.app
- 最低系统：Android 5.1（minSdk 22）
- 无平台水印、无广告（App 内已隐藏网页上的「豆包AI生成」标识）
- 构建方式：GitHub Actions 云端自动编译并签名，无需本地配置安卓环境
- 应用内一键更新：以后壳层修复，App 自动检测新版本，点一下即可下载安装，无需手动传文件

> **两类更新的区别**：网站内容（照片、视频、页面功能）改动，App 重开即自动同步，无需更新；只有 App 壳层（键盘、权限、图标等原生配置）修复才需要走下面的「应用内一键更新」，而这类改动在适配稳定后基本不会再有。

---

## 一、在 GitHub 建仓库并推送

1. 在 GitHub 新建一个仓库，例如命名为 `lykke`（可设为私有）。
2. **（必做）配置更新地址**：打开 `android/app/src/main/java/com/lykke/app/UpdateManager.java`，把顶部
   `private static final String REPO = "your-name/lykke";`
   改成你的「用户名/仓库名」，例如 `private static final String REPO = "zhangsan/lykke";`
3. 把本工程的**全部文件**推送到仓库（注意：`lykke.keystore`、`keystore.properties`、`node_modules` 已被 `.gitignore` 排除，不会上传）。

   ```bash
   cd lykke
   git init
   git add .
   git commit -m "init lykke"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/lykke.git
   git push -u origin main
   ```

---

## 二、配置 4 个仓库 Secrets

打开 GitHub 仓库页面：**Settings → Secrets and variables → Actions → New repository secret**，依次添加以下 4 个：

| Secret 名称 | 值 |
|---|---|
| `LYKKE_KEYSTORE` | 见「签名信息-请妥善保管.txt」里的一整段 base64（一行，很长） |
| `LYKKE_KEYSTORE_PASSWORD` | 签名密码 |
| `LYKKE_KEY_ALIAS` | `lykke` |
| `LYKKE_KEY_PASSWORD` | 签名密码（与上一个相同） |

> 具体值都在随工程交付的「签名信息-请妥善保管.txt」里。

---

## 三、自动构建 APK

1. 推送代码后，进入仓库 **Actions** 页面，会看到 **Build Android APK** 工作流自动运行（约 5–8 分钟）。
2. 也可在 Actions 页面手动点 **Run workflow** 触发。
3. 构建成功后，点进该次运行，在页面底部 **Artifacts** 里下载 **lykke-release-apk**，解压得到 `app-release.apk`。
4. 同时，工作流会**自动把该版本发布到仓库的 Releases**（标签 `v<版本号>`），这是 App 内一键更新的来源。

---

## 四、安装到手机

1. 把 `app-release.apk` 发到手机（微信/QQ/网盘/USB 均可）。
2. 点击安装，首次会提示「允许安装未知来源应用」，允许即可。
3. 华为/荣耀设备：若提示「该应用存在风险/纯净模式拦截」，选择「继续安装」或在设置里关闭「纯净模式」后再装。
4. 装好后打开，就是你们的纪念网站，用原有账号登录即可。

---

## 五、以后升级 App（应用内一键更新）

当你需要发新版本（例如改了图标、名称或 App 配置）：

1. 打开 `android/app/build.gradle`，把 `versionCode` 每次 +1（如 1→2），`versionName` 递增（如 `1.0`→`1.1`）。
2. 提交并推送，Actions 自动构建并发布到 Releases（标签 `v1.1`）。
3. 用户手机上的 App 启动后会自动检测到新版本，弹窗「有新版本」，点**立即更新**即自动下载并调起安装，覆盖安装即可（同一 keystore，数据不丢）。
4. 首次覆盖安装时，系统可能提示「允许该应用安装未知应用」，允许一次即可；若尚未授权，App 会先弹提示并自动跳到本应用对应的开关页，打开后返回即继续安装，该权限只需授权一次。

> 注意：网站本身的内容更新不需要重新打 APK，App 里会自动显示最新内容。

---

## 六、常见问题

- **打开是白屏/一直加载**：检查手机网络，杀掉 App 重开；网站需要联网使用。
- **Actions 构建失败**：点进失败的步骤看日志；最常见是 4 个 Secret 没配齐或 base64 复制不完整。
- **覆盖安装提示签名不一致**：说明用了不同 keystore；请用本工程的原始 keystore，或先卸载旧版再装（会清掉 App 本地缓存，不影响云端数据）。
- **硬件加速回退**：1.6 起 WebView 恢复 GPU 硬件渲染以提升滑动/动画流畅度；若个别机型出现画面闪烁、透明层异常，或登录密码框被自动填充清空，把 `MainActivity.setupFocus()` 里的 `LAYER_TYPE_HARDWARE` 改回 `LAYER_TYPE_SOFTWARE` 重新出包即可。
- **登录态**：与网页一致，账号密码不变。

---

## 七、重要提醒

- `lykke.keystore` 和签名密码是 App 的「身份」，请务必**离线多处备份**；丢失后无法再出覆盖升级包。
- 证书与密码不要提交到 Git、不要泄露。
