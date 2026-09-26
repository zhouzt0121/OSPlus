package com.osplus.tools.ui.components

import android.graphics.RenderEffect as PlatformRenderEffect
import android.graphics.RuntimeShader as PlatformRuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * 液态玻璃的**折射**着色器（AGSL）。
 *
 * 这是「液态玻璃」与「普通毛玻璃」真正的分界线。
 *
 * 毛玻璃只做一件事：把背景采样出来、均匀模糊、再叠一层半透明白。
 * 结果是背景被糊成一片匀质的底噪，控件没有任何「厚度感」——
 * 它看起来像一层磨砂塑料，而不是一块能折弯光线的实体玻璃。
 *
 * 真正的玻璃会在**边缘**改变光的路径：越靠近轮廓，背景被拉扯得越明显，
 * 中心区域则接近无畸变。这个「中心为零、边缘最强」的梯度，
 * 正是人眼判断「这是玻璃」的关键线索——所以折射不能省，光靠模糊堆半径堆不出来。
 *
 * 完整链路（本文件负责「折射」与「效果链拼装」两环）：
 *
 * ```
 * 背景 → 模糊 → 折射 → 玻璃着色 → 高光 → 非均匀描边 → 图标
 *               ^^^^
 * ```
 *
 * ### 位移场由「圆角矩形 SDF」给出，不是「到中心的距离」
 *
 * 这是实测踩出来的坑。第一版用 `d = length(coord - center) / halfDiagonal`：
 * 它在四角才等于 1，四条边的中点只有 0.707——于是折射全挤在胶囊两端的圆弧上，
 * 长直边几乎零畸变，真机上看起来就是「没生效」。
 *
 * 改用圆角矩形有符号距离场后，轮廓一圈的 `sd` 恒为 0，
 * `k = 1 - smoothstep(-uBand, 0, sd)` 让**整圈边缘**都进入折射，
 * 而 `sd < -uBand` 的内部区域严格保持 k = 0。环带宽度 `uBand` 独立可调，
 * 不再与控件宽高比纠缠。
 *
 * ### 为什么位移方向取「向内」
 *
 * 采样点沿法线朝内收（`coord - n * k * uStrength * uBand`），于是边缘处的背景
 * 被挤压、拉出一圈透镜环；反过来向外推则更像放大镜扣在屏幕上，悬浮控件不该有那种观感。
 *
 * ### 为什么位移量按像素算
 *
 * 位移是 `k * uStrength * uBand`，全部是像素量纲：`uBand` 是环带宽（px），
 * `uStrength` 是环带内的最大位移占带宽的比例。若改用归一化坐标，
 * 在宽高比悬殊的胶囊上畸变会被各向异性拉扁（长边方向位移巨大、短边几乎为零）。
 * 用像素量纲后畸变与控件尺寸解耦，条变长也不会改变观感。
 *
 * ### 模糊半径不能太大
 *
 * 折射要能被看见，背景必须留下**可辨认的结构**当参照物。
 * 半径 26dp 时背景细结构（一行小字、一条 1px 的趋势线）已被糊成匀质底噪，
 * 采样点位移多少都没有对比——真机上表现为「折射完全看不出来」，
 * 但其实是模糊把参照物抹掉了。因此半径收到 14dp 量级，
 * 由 [com.osplus.tools.ui.components.OsFloatingBottomBar] 侧的
 * `GlassBlurRadius` 控制。
 *
 * ### 平台侧与 Compose 侧的桥
 *
 * Compose 1.12 的 `androidx.compose.ui.graphics` **没有** `RuntimeShader` 类，
 * 也没有 `RenderEffect.createRuntimeShaderEffect` / `createChainedEffect`
 * （该模块只暴露了 `createBlurEffect`）。这两个工厂都在平台侧：
 * `android.graphics.RuntimeShader`（API 33+）与 `android.graphics.RenderEffect`。
 *
 * 因此这里用平台 API 构造，再用公开扩展函数
 * `android.graphics.RenderEffect.asComposeRenderEffect()` 桥回 Compose 的 `RenderEffect`
 * ——`BackdropEffectScope.renderEffect` 收的是 Compose 类型，必须过这一道。
 *
 * 注意**不能**用 `androidx.compose.ui.graphics.AndroidRenderEffect` 这个类来包装：
 * 它在字节码里是 public，但 Kotlin 侧标了 internal，跨模块调用会直接编译失败。
 * 同理，链式效果在平台侧叫 `createChainEffect(outer, inner)`（不是 `createChainedEffect`），
 * 且 Compose 侧根本没有对应函数。
 *
 * 失败时返回 null，调用方退回纯模糊——AGSL 编译失败是平台行为，
 * 宁可降级也不能让导航条整体崩掉。
 */
private const val REFRACTION_AGSL = """
uniform shader child;
uniform float2 uSize;
uniform float uRadius;
uniform float uBand;
uniform float uStrength;
uniform float uDispersion;
uniform float uDepth;

// 圆角矩形的**解析梯度**：直边上给出轴对齐法线，圆角处给出径向法线。
// 用 normalize(p) 近似在直边上会把法线拉成斜的，整圈折射方向跟着歪掉。
float2 gradSd(float2 c, float2 hs, float r) {
    float2 q = abs(c) - (hs - float2(r));
    if (q.x >= 0.0 || q.y >= 0.0) {
        return sign(c) * normalize(max(q, float2(0.0)));
    }
    float gx = step(q.y, q.x);
    return sign(c) * float2(gx, 1.0 - gx);
}

half4 main(float2 coord) {
    float2 hs = uSize * 0.5;
    float2 p = coord - hs;

    // 圆角矩形 SDF：轮廓上为 0，内部为负。
    // 不用「到中心的距离」是因为它在四条边的中点只有 0.707、只有四角才到 1——
    // 结果是畸变全挤在两端圆弧上，长直边几乎看不到折射。
    float2 q = abs(p) - hs + uRadius;
    float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;

    // 环带之外直接返回原像素：只有轮廓内侧 uBand 像素那一圈付折射的代价。
    // 中心占整块玻璃的绝大部分面积，这一步把它们从 3 次取样降到 1 次。
    if (-sd >= uBand) return child.eval(coord);

    // 位移用圆弧剖面，不用 smoothstep。
    // circleMap 正是圆形透镜的矢高曲线，位移从环带内缘的 0 平滑升到轮廓处的最大值，
    // 观感才像一块磨出来的透镜；smoothstep 的 S 形曲线在靠近轮廓时会提前压平。
    float t = clamp(-sd / uBand, 0.0, 1.0);
    float x = 1.0 - t;
    float d = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * uStrength * uBand;

    // 法线再叠一个径向分量：> 0 时玻璃「中间鼓起来」，有了厚度
    float2 n = normalize(gradSd(p, hs, uRadius) + uDepth * normalize(p + float2(1e-6, 0.0)));
    float2 sp = coord - n * d;

    // 色散只在**四角**出现：乘上 (x*y)/(hx*hy) 后直边中段为 0、四角最强。
    // 整圈都上色散会像镜头没校准；只在转角处留一点彩边才像玻璃。
    float e = uDispersion * ((p.x * p.y) / (hs.x * hs.y));
    half4 mid = child.eval(sp);
    half4 red = child.eval(sp + n * e);
    half4 blue = child.eval(sp - n * e);
    return half4(red.r, mid.g, blue.b, mid.a);
}
"""

/**
 * 折射环带宽度（px）：从轮廓往里算，只有这一圈参与折射。
 *
 * 带宽太窄（< 8px）时位移只发生在最后几个像素里，肉眼来不及看出「折弯」；
 * 太宽（> 32px）则会吃掉玻璃内部太多面积，中心无畸变区被压得只剩一条。
 */
private const val RefractionBandPx = 20f

/**
 * 环带内的最大位移，以 [RefractionBandPx] 为单位。
 *
 * 0.9 表示轮廓处最多把采样点沿法线拉进来 0.9 × 20 = 18px。
 * 实测区间：**< 0.4 基本看不出**；**> 1.4 会把背景撕出可见重影**，
 * 尤其当下方正好滚过一行小字时。
 *
 * 注意：折射能不能被看见，**首先取决于模糊半径与玻璃不透明度**，其次才是这个值。
 * 背景被糊成匀质底噪、或被实色盖到 0.55 以上，再大的位移也没有参照物。
 */
private const val RefractionStrength = 0.9f

/**
 * 边缘色散（px）。
 *
 * R / B 通道沿法线各偏移这么多像素。取 1px 量级：够在轮廓处留下一条肉眼可辨、
 * 但不会被认为是「渲染错误」的彩边。0 表示关闭（退化为纯位移折射）。
 */
private const val RefractionDispersionPx = 1.1f

/**
 * 折射法线里的径向分量（0 = 关闭，与参考实现 `depthEffect = false` 一致）。
 *
 * > 0 时法线会额外朝「从中心向外」偏一点，玻璃于是有了中间鼓起的厚度，
 * 更像一块透镜而不是一圈等厚的边框。取值要小：0.15 量级已足够，
 * 再大会让直边上的法线不再垂直于边缘，整块玻璃看起来像被吹胀了。
 */
private const val RefractionDepth = 0f

/** AGSL 与运行时着色器需要 Android 13（API 33）；低于此版本整条折射链降级 */
private fun runtimeShaderSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * 折射一环。返回**平台**类型，供拼链使用。
 *
 * 刻意不在这里转成 Compose 类型：`createChainEffect` 只吃平台类型，
 * 而 Compose → 平台的 `asAndroidRenderEffect()` 同样是 internal，
 * 一旦转过去就再也回不到平台侧拼链了。
 * 所以整条链都在平台侧拼完，只在最后一步桥回 Compose（见 [buildLiquidGlassEffect]）。
 *
 * @param widthPx 玻璃区域的宽（px），SDF 需要知道半宽
 * @param heightPx 玻璃区域的高（px）
 * @param cornerRadiusPx 圆角半径（px）。**必须与 `liquidGlass` 的 `cornerRadius` 一致**，
 *   否则 SDF 描述的轮廓与真实轮廓错位，折射环会跑到玻璃外面去。
 * @return null 表示本机不支持（API < 33）或 AGSL 编译失败
 */
private fun buildRefraction(
    widthPx: Float,
    heightPx: Float,
    cornerRadiusPx: Float,
): PlatformRenderEffect? {
    if (!runtimeShaderSupported()) return null
    if (widthPx <= 0f || heightPx <= 0f) return null
    return runCatching {
        val shader = PlatformRuntimeShader(REFRACTION_AGSL)
        shader.setFloatUniform("uSize", widthPx, heightPx)
        // 半径超过短边的一半时 SDF 会自交（退化成椭圆角），夹一下更安全
        val maxRadius = minOf(widthPx, heightPx) * 0.5f
        shader.setFloatUniform("uRadius", cornerRadiusPx.coerceIn(0f, maxRadius))
        // 环带同样不能超过短边的一半，否则整个控件都在折射
        shader.setFloatUniform("uBand", RefractionBandPx.coerceAtMost(maxRadius))
        shader.setFloatUniform("uStrength", RefractionStrength)
        shader.setFloatUniform("uDispersion", RefractionDispersionPx)
        shader.setFloatUniform("uDepth", RefractionDepth)
        PlatformRenderEffect.createRuntimeShaderEffect(shader, "child")
    }.getOrNull()
}

/**
 * 拼出液态玻璃的完整效果链：**模糊 → 折射**，一步到位。
 *
 * 调用方（悬浮导航条）在 `drawBackdrop` 的 effects 里先调一次 `blur(radius)`——
 * 那一步除了画模糊，还会设置 padding / downscaleFactor 等采样参数，
 * 这些副作用必须保留，所以不能省掉它。本函数返回的链随后**整体覆盖**
 * `renderEffect`：链里自带一道平台模糊，于是「模糊 + 折射」一起生效。
 *
 * 任一步构造失败都返回 null，调用方保留 `blur()` 已写入的结果，
 * 降级为普通毛玻璃——旧机型（API < 33）与 AGSL 编译失败都走这条退路。
 */
fun buildLiquidGlassEffect(
    blurPx: Float,
    widthPx: Float,
    heightPx: Float,
    cornerRadiusPx: Float,
): RenderEffect? {
    if (!runtimeShaderSupported()) return null
    if (widthPx <= 0f || heightPx <= 0f) return null
    val refraction = buildRefraction(widthPx, heightPx, cornerRadiusPx) ?: return null
    return runCatching {
        val blur = PlatformRenderEffect.createBlurEffect(
            blurPx,
            blurPx,
            Shader.TileMode.CLAMP,
        )
        // createChainEffect(outer, inner)：outer 作用在 inner 的结果之上。
        // 要「先模糊、再折射」，就得 outer = 折射、inner = 模糊；写反了折射会被模糊抹平。
        PlatformRenderEffect.createChainEffect(refraction, blur).asComposeRenderEffect()
    }.getOrNull()
}
