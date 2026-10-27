# hongguo3-android

红果短剧 **原生 Android 客户端**（不是 WebView 壳，也不是桌面版的远程遥控器）。

装上就能用：不需要电脑、不需要填地址、不需要额外装 Python / Java / 模拟器。

## 和桌面版（V3）的差别只有一处

| | 桌面版 | 本仓库 |
|---|---|---|
| 运行环境 | Python + JRE 17 + unidbg（x86 上**模拟**一颗 ARM64 CPU 跑签名 so） | 真机就是 ARM64，`dlopen` 直接跑原生机器码 |
| 体积 | 约 260 MB | APK 约 12 MB |
| 播放链路 | 解密后起本地 HTTP 服务 + "虚拟明文 MP4" 骗浏览器 `<video>` | 解密出明文文件，直接交 ExoPlayer |
| 依赖 | 必须先在本机跑后端 | 无 |

所以这里没有"后端"这个概念：签名、取流、解密全部在 App 进程内完成。

## 三条核心链路

### 1. 签名（`X-Argus` / `X-Gorgon`）

上游接口对剧集详情、播放地址强制校验签名，免签会返回**空 body**。

签名的算法在 `libmetasec_ml.so` 里（ELF64 AArch64，C++ 静态链接，只依赖 `liblog/libandroid/libm/libdl/libc`）。桌面版只能靠 unidbg 模拟执行；手机上直接：

1. `System.loadLibrary("metasec_ml")` —— so 的 `JNI_OnLoad` 会
   `RegisterNatives(ms/bd/c/m, a(IIJLjava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;)`，
   也就是把 native 入口绑到 `ms.bd.c.m.a`；
2. 真正的签名函数是个**净函数**（入参两个字符串、返回签名串），不在导出表里，
   所以用 `libmetasec_ml.so` 的加载基址 + 偏移 `0x27d288` 定位（见 `cpp/signer.c`）。

配套的三个 Java 类必须保留，否则 so 加载即崩：

- `ms.bd.c.m` —— 唯一带 `native` 声明的方法（so 注册进来的）
- `ms.bd.c.a4$a` —— so 内部 `FindClass` 要用
- `com.bytedance.mobsec.metasec.ml.MS` —— so **回调**的 Java 方法（反调试查调用栈），
  **不能**声明成 native

> 偏移 `0x27d288` 只对当前这份 so 有效，换 so 要重新定位。

### 2. 取流

`/novel/player/multi_video_detail/v1/` 拿剧集 → `/novel/player/multi_video_model/v1/`
拿各清晰度直链 + `encrypt_info.spade_a`。

### 3. 解密（CENC → 明文）

上游是 CENC 加密的 MP4（`encv`/`enca` 样本条目 + `senc`/`saiz`/`saio`），系统播放器不认。

- `spade_a` 经纯字节变换（XOR + POPCOUNT + 位置相关，无 KEK / 无 AES）解出 32 字符 content key（`Spade.java`）
- 按 `stsz/stco/stsc` 展开样本，逐样本 AES-128-CTR 解密（`Ctr.java`）
  - IV 128 位：高 64 = `base_iv + 样本序号`，低 64 = 块号
- 同时做等长补丁：`encv→hvc1`、`enca→mp4a`、`sinf/senc/saiz/saio→free`（`Moov.java`）

解完就是普通 MP4，ExoPlayer 直接播。

## 目录

```
app/src/main/cpp/signer.c            签名桥（基址 + 偏移，JNI）
app/src/main/jniLibs/arm64-v8a/      libmetasec_ml.so
app/src/main/assets/config.json      接口 host / base_query / session_headers
app/src/main/java/ms/bd/c/           so 要求的宿主类
app/src/main/java/com/hongguo/client/
    Signer.java                      签名封装 + 诊断
    Api.java                         榜单 / 剧集 / 直链 / 下载
    cenc/                            Boxes Moov Ctr Spade Decryptor
    MainActivity.java                首页（榜单 / 收藏 / 历史）
    PlayActivity.java                播放（选集 / 清晰度 / 续播）
```

## 构建

本地需要 JDK 17 + Android SDK 34 + NDK 26.1.10909125 + CMake 3.22.1：

```bash
gradle assembleRelease   # 产物 app/build/outputs/apk/release/app-release.apk
```

不装环境也行：推一个 commit，GitHub Actions 会编译并把 APK 传到 artifact（`build-android`）。

release 用的是 AGP 自带 debug 签名，可直接 `adb install`。

## 已知限制

- 只编 `arm64-v8a`（so 只有这一份；x86 模拟器跑不了）
- 包名是 `com.hongguo.client`，与上游官方包名不同；若 so 内部有包名校验会签出空值 →
  首页右上角「诊断」可直接看签名状态
- so 是 4 KB 页对齐，Android 15 的 16 KB 页机型可能加载失败
- root / Frida 环境会触发反调试（`MS.b` 查调用栈），签名结果可能为空
