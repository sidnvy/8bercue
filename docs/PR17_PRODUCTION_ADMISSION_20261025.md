# PR #17：正式无线配对产物的验收与准入方案

本文件是待审核方案，不是正式发布授权。没有创建新标签、上传 APK 或发布版本。

## 已取得的证据

- 新 Lab 候选 APK SHA-256：`07f09a130b468dc64467c135b3d5b8b10d491431ba4651b78964d32956ad9915`。
- Lab 签名：`3a1ebed312d2f5ec257cde4a2966e3b8da7533be20b014bd77a48e171f6329ea`。
- 打包的 JNI SHA-256：`10f9509cfbd28825f5f4ecc5f05a2e91348c7f350987c568c4a8aa4f5e009c1e`，与此前源码构建一致。
- 已重新通过 Lab/Release 构建、两种 Lint、2231 项本地化断言、Lab 完整产物验证、普通未签名 Release 产物验证。
- BoringSSL 锁定源码重新构建通过，ARM64、16 KiB 对齐、动态依赖检查通过；未替换候选 APK 的 JNI。
- 已公开的正式证书实际下载后 SHA-256 为 `24aa7984b0d0977ff1e87b57beca91b2e36a05c04a5443f1a47aa23d90a6ef1c`。
- GitHub `beta-release` 已有四个签名输入名称；没有读取 secret 值。受保护签名 smoke 在 main 的 `91564e7` 成功过：<https://github.com/sidnvy/8bercue/actions/runs/39735032895>。这不是当前无线候选的正式签名验收。
- 生产验证器实际拒绝 Lab APK 和未签名 Release；未削弱生产门禁。

## 正式候选必须满足的具体条件

1. **原生库准入。** 普通 Release 目前仍排除配对 JNI。若决定纳入，需要明确审核后的构建与产物契约：只允许 ARM64 的现有 `libeuicc-drivers.so` 和新增 `lib8bercue_adb_pairing.so`；现有 LPAC 资产清单、资产哈希、禁止的类/依赖、隐私资源、备份关闭、非 debuggable、16 KiB 对齐要求都必须保留。不能仅放宽为“允许任意 .so”。
2. **来源与许可。** 配对 JNI 必须仅来自锁定源码构建，并保留完整 BoringSSL/OpenSSL/SSLeay/ISC/MIT 声明。需要完成项目 AGPL-3.0-only、Apache-2.0 JNI 及这些第三方条款组合的许可审查；已有 notices 不是兼容性审查结论。本轮没有改变许可证或添加例外授权。
3. **源码 CI。** 已准备 `native-source-ci.patch`，只把历史 AAR 构建入口换为锁定上游源码构建。当前 OAuth 缺少 workflow 权限；需维护者明确授权后应用。不得把旧 AAR CI 的绿色结果称为新源码 CI 已通过。
4. **受保护签名。** 使用既有 `beta-release` 环境和正式证书，针对最终准入的精确源码/产物重新验证。现有签名 workflow 只允许 main；不能把旧 main smoke 或 Lab 签名当作本候选的正式签名结果，也不能为方便测试绕过环境审批。
5. **升级与身份。** 正式签名升级应从正式签名旧版验证。Lab 与正式签名不同；不允许承诺直接跨签名保留 App 数据/Keystore，也不导出私钥或暗中卸载当前新配对实例。
6. **版本与源码交付。** 当前开发版本仍为已发布的 `0.19.1-beta.3 / 48`。正式发布应选择新版本，生成与该精确版本相符的说明、readiness 数据和源码清单，再校验不可变源码归档。历史发布的 `BETA-READINESS.json` 不应被当作当前候选的验收记录。
7. **最终运行验收。** 对最后签名的 APK 再做首次配对、重启 App 后重连、认证 Binder 和指定卡片只读验证；卡片写操作不从只读结果推定通过。

现有 Release 安全验证器和发布 workflow 本轮均未修改。正式候选准入后应以具体代码/产物继续审核，而不是把本 Lab APK 重新命名为 Release。
