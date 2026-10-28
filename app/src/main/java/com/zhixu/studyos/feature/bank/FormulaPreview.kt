package com.zhixu.studyos.feature.bank

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private const val FORMULA_BASE_URL = "file:///android_asset/katex/"
private const val FORMULA_MIN_HEIGHT_DP = 36
private const val FORMULA_MAX_HEIGHT_DP = 320

/**
 * LaTeX 公式预览（V0.8 第三节）。
 *
 * KaTeX（MIT 许可）随 APK 打包在 assets 中，在本地 WebView 内离线渲染，不联网。
 * 渲染失败时回退为显示 LaTeX 原文，提示人工校对。
 *
 * 实现要点：
 * - JS 桥接对象只创建一次（remember），通过可变回调转交结果，避免 LaTeX 变化后
 *   旧闭包写入已废弃的状态（否则渲染失败不会回退）；
 * - 只有 HTML 内容变化时才重新加载 WebView，避免每次重组都重载造成闪烁。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun FormulaPreview(
    latex: String,
    modifier: Modifier = Modifier,
    displayMode: Boolean = false,
) {
    var renderFailed by remember(latex, displayMode) { mutableStateOf(false) }
    var measuredHeightDp by remember(latex, displayMode) { mutableStateOf<Int?>(null) }
    val background = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val bridge = remember { FormulaBridge() }

    if (renderFailed) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(background),
        ) {
            Text(
                text = latex,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                softWrap = false,
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
            )
        }
        return
    }

    bridge.onResult = { success, cssHeight ->
        if (success) {
            measuredHeightDp = cssHeight.coerceIn(FORMULA_MIN_HEIGHT_DP, FORMULA_MAX_HEIGHT_DP)
        } else {
            renderFailed = true
        }
    }

    val html = remember(latex, displayMode, background, textColor) {
        buildFormulaHtml(latex, displayMode, background, textColor)
    }

    AndroidView(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .height((measuredHeightDp ?: FORMULA_MIN_HEIGHT_DP).dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // KaTeX 资源位于 file:///android_asset/katex/，需要文件访问；不加载任何远程内容。
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                isVerticalScrollBarEnabled = false
                addJavascriptInterface(bridge, "StudyOSFormula")
                bridge.attach(this)
                tag = html
                loadDataWithBaseURL(FORMULA_BASE_URL, html, "text/html", "utf-8", null)
            }
        },
        update = { webView ->
            // 仅在 HTML 变化时重新加载，避免每次重组都重载导致闪烁
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(FORMULA_BASE_URL, html, "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * 固定生命周期的 JS 桥接。WebView 的 @JavascriptInterface 方法在后台线程调用，
 * 因此所有回调都 post 到 WebView 主线程。
 */
private class FormulaBridge {
    var onResult: ((success: Boolean, cssHeight: Int) -> Unit)? = null
    private var host: WebView? = null

    fun attach(webView: WebView) {
        host = webView
    }

    @JavascriptInterface
    fun onRendered(success: Boolean, cssHeight: Int) {
        val view = host ?: return
        view.post { onResult?.invoke(success, cssHeight) }
    }
}

private fun buildFormulaHtml(
    latex: String,
    displayMode: Boolean,
    background: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
): String {
    // JSON 字符串字面量本身就是合法的 JS 表达式，直接内插。
    val payload = JSONObject().put("latex", latex).toString()
    val bg = Integer.toHexString(background.toArgb())
    val fg = Integer.toHexString(textColor.toArgb())
    val displayModeJs = if (displayMode) "true" else "false"
    return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<link rel="stylesheet" href="katex.min.css">
<script src="katex.min.js"></script>
<style>
  html, body { margin: 0; padding: 0; background: #$bg; color: #$fg; }
  #formula { padding: 6px 10px; overflow-x: auto; }
</style>
</head>
<body>
<div id="formula"></div>
<script>
  (function () {
    var payload = $payload;
    var target = document.getElementById('formula');
    try {
      katex.render(payload.latex, target, {
        displayMode: $displayModeJs,
        throwOnError: true,
        strict: false,
        trust: false
      });
      var height = Math.ceil(target.getBoundingClientRect().height) + 12;
      window.StudyOSFormula.onRendered(true, height);
    } catch (error) {
      window.StudyOSFormula.onRendered(false, 0);
    }
  })();
</script>
</body>
</html>
"""
}
