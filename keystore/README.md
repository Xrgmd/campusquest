# keystore

`cq-release.keystore` 是本项目 release 构建用的签名密钥(Android 调试密钥,别名 `androiddebugkey`,口令 `android`)。

**为什么把它放进仓库**:0.2.2 起所有版本都用同一把密钥签名,手机上的数据才能在覆盖安装时保留。
如果换成另一把密钥,`adb install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,唯一出路是卸载重装 —— 而卸载会清掉全部课程与任务。

**如果你要正式分发**:请生成自己的密钥(`keytool -genkeypair ...`),替换 `app/build.gradle.kts` 里的 signingConfig,
并在 README 里声明 —— 但这会让老用户无法覆盖安装。

由于这是公开的调试密钥,任何人都能签出「看起来能覆盖安装」的 APK。
从他人处获取安装包时请自行校验签名 SHA-256:
`e41194a36ac9a0ce26507953b04dfde0ef197e8e382deb499e89ee859a3d2937`。