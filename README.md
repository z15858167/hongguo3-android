# hongguo3-android

红果短剧 **原生 Android 客户端**（不是 WebView 壳，也不是桌面版的远程遥控器）。

装上就能用：不需要电脑、不需要填地址、不需要额外装 Python / Java / 模拟器。

## 下载安装

GitHub Actions 每次构建成功后会更新同一个预发布：

**https://github.com/z15858167/hongguo3-android/releases/tag/latest**

APK 在「Assets」里（`app-release.apk`），手机下载后允许「未知来源」即可安装。

> 第一次打开要联网（取榜单、取签名所需数据），之后播放过的剧会在本地留明文缓存。

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
2. 真正的签名函数是 so 内部的**净函数**，不在导出表里，所以按「加载基址 + 偏移」调用。
   两个候选，代码里都试探一遍（见 `cpp/signer.c`）：

   | 偏移 | 调用约定 | 来源 |
   |---|---|---|
   | `0x168c80`（首选） | `sign(url, header)` 2 参数 | `upstream/.../FqTrace.java` 的 `SIGN`，**已实测出真签名** |
   | `0x27d288`（兜底） | `sign(url, header, 0x18, tick, 0xffffffffffffff, counter)` 6 参数 | `MetasecSign.java` 的 `SIGN_OFFSET` |

   `0x27d288` 落在本 so 的 `.rodata`（`.text` 只到 `0x268d64`），直接跳过去必崩，
   所以调用前先查 `/proc/self/maps` 确认目标地址可执行，不可执行就跳过。

配套的三个 Java 类必须保留，否则 so 加载即崩：

- `ms.bd.c.m` —— 唯一带 `native` 声明的方法（so 注册进来的）
- `ms.bd.c.a4$a` —— so 内部 `FindClass` 要用
- `com.bytedance.mobsec.metasec.ml.MS` —— so **回调**的 Java 方法（反调试查调用栈），
  **不能**声明成 native

> 偏移只对你手上这一份 `libmetasec_ml.so` 有效（md5 变了就要重新定位）。
> so 通过 `JNI_OnLoad` 注册 native 时会回调 `MS.b(...)` 问环境；App 把证书
> `assets/ms_16777218.bin`、版本号、`.msdata` 路径都照着桌面版的实测值补齐了。

## 功能（对齐 V3）

首页榜单（推荐 / 热播 / 新剧）、收藏、观看历史（可清空）、搜索（含联想词）、
分类浏览（短剧 / 漫剧 / AI 剧 + 主题·设定·背景·排序·受众·时间·状态筛选，滚动翻页）。

播放页（v0.8.0，对齐红果原生）：

| 项 | 行为 |
|---|---|
| 启动 | 直接进 feed 并播推荐第一部第 1 集；播完自动进第 2 集 |
| feed | 视频铺满整屏，左下「剧名 / 标签 / 第N集｜简介 / 观看完整短剧·全N集」，右侧追剧，底部**只有进度条 + 首页｜剧场**（没有全屏按钮）；上下滑 = 切整部剧 |
| 进详情 | 点「观看完整短剧」**0ms 硬切**：视频位置与尺寸**完全不变**，只去掉左下文字，底部出现一行 `选集 N集 ｜ 1.0x ｜ 1080P ｜ ⛶`（进度条贴屏幕最底、按钮行在它上方） |
| 选集 | 白底面板**上推 260ms**（55% 屏高、无封面无简介 tab、1-30 分页、6 列、当前集浅橙+播放中）；点某一集 → 立即切集 + 面板回落 200ms |
| 倍速 | 档位 `0.75/1.0/1.25/1.5/2.0`（上限 2.0）；**长按恒 2.0x，松手回手动设定档** |
| 清晰度 | 贴按钮朝上弹出的选择窗；切档**保留当前进度**重新取流 |
| 手势 | 单击=暂停/播放、长按=2x、上下滑=切剧(feed)/切集(详情与全屏)、左右滑=快进快退、双击已取消 |
| 秒切 | `PlayerEngine` 双 ExoPlayer 槽位，空闲槽预载下一条（读窗 = 读前沿 + 16MB），切换只做 surface 对开 + activate |

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
    cenc/                            Boxes Moov Ctr Spade Decryptor StreamSource
    FeedQueue.java                   feed 节目单（榜单摊平 + 跨剧展开）
    PlayerEngine.java                双 ExoPlayer 槽位（预载 / 秒切）
    EpisodeSheet.java                白底分页选集面板
    Cache.java / Stats.java          1GB 缓存水位 / 自检打点
    MainActivity.java                剧场（榜单 / 收藏 / 历史）
    PlayActivity.java                播放（feed + 详情 + 全屏）
```

## 构建

本地需要 JDK 17 + Android SDK 34 + NDK 26.1.10909125 + CMake 3.22.1：

```bash
gradle assembleRelease   # 产物 app/build/outputs/apk/release/app-release.apk
```

不装环境也行：推一个 commit，GitHub Actions 会编译 + 发布到 `latest` 预发布（`build-android`）。
历史构建产物在 Actions 的 Artifacts 里保留 30 天。

release 用的是 AGP 自带 debug 签名，可直接 `adb install`。

### 推送前的本地体检（必跑，ghpush 自动带上）

`tools/ghpush.py` 每次推送前会先跑 `tools/preflight.py`，不通过就不推。
它静态检查那些"只有真机才能发现"的硬错误，例如：

- **phdr 表必须落在某个 PT_LOAD 段内**（v0.1.1 就是踩了这个：重排脚本把 phdr 表
  搬到文件末尾，bionic 报 `loaded phdr not in loadable segment`，so 加载即失败）
- 每个 LOAD 段 `p_offset ≡ p_vaddr (mod 页大小)`
- PT_DYNAMIC 偏移在 LOAD 内、NEEDED 依赖齐全、Manifest/资源 XML 合法等

> 本机 `github.com` 不通（只有 `api.github.com` 通）时，`git push` 会失败，
> 可以用仓库里的 `tools/ghpush.py`：它走 GitHub Git Data REST API 手动
> `blob → tree → commit → ref`，效果和 push 一样。
>
> ```bash
> python3 tools/ghpush.py "提交说明"
> ```

## 已知限制

- 只编 `arm64-v8a`（so 只有这一份；x86 模拟器跑不了）
- 仓库里是上游原始的 4 KB 页对齐 so（在绝大多数设备上验证可加载）。
  Android 15 的 16 KB 页机型如需支持：`cp 原始so 备份 && python3 tools/align16k.py app/src/main/jniLibs/arm64-v8a/libmetasec_ml.so`
  再编译即可（v2 版脚本不会再把 phdr 表搬出 LOAD 段——那是 v0.1.1 加载失败的根因）；
  `tools/preflight.py` 会对 4KB / 16KB 两种版本都做静态校验
- 包名是 `com.hongguo.client`，与上游官方包名不同；若 so 内部有包名校验会签出空值 →
  首页右上角「诊断」可直接看签名状态
- root / Frida 环境会触发反调试（`MS.b` 查调用栈），签名结果可能为空
