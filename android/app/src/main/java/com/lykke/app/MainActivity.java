package com.lykke.app;

import android.os.Build;
import android.view.View;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {

    private boolean updateChecked = false;
    private boolean listenerRegistered = false;
    private UpdateManager updateManager;

    // 干净正式版脚本：隐藏平台水印 + 抑制系统自动填充 + 聚焦时值保护；不含诊断、不动态切换
    private static final String INJECT_JS = """
        (function(){
          if (window.__lykkeClean) return;
          window.__lykkeClean = true;

          // ===== 隐藏平台水印（仅 App 内生效，网页本身不变）=====
          var KEYWORDS = ['aigc','watermark','ai-badge','aibadge','ai_badge','aigenerated','aigc-badge','doubao','ai-gen','generated-by','ai_pill'];
          function isBadge(el){
            if(!el || el.nodeType !== 1) return false;
            var cs;
            try { cs = window.getComputedStyle(el); } catch(e){ return false; }
            if(cs.position !== 'fixed' && cs.position !== 'absolute') return false;
            var cls = ((typeof el.className === 'string' ? el.className : '') + ' ' + (el.id || '')).toLowerCase();
            for(var k=0;k<KEYWORDS.length;k++){ if(cls.indexOf(KEYWORDS[k]) !== -1) return true; }
            var txt = (el.innerText || el.textContent || '').trim();
            if(txt && txt.length <= 24 && (txt.indexOf('豆包') !== -1 || txt.indexOf('AI') !== -1 || txt.indexOf('生成') !== -1)) return true;
            return false;
          }
          function hide(el){
            try{
              el.style.setProperty('display','none','important');
              el.style.setProperty('visibility','hidden','important');
              el.style.setProperty('opacity','0','important');
            }catch(e){}
          }
          function scan(root){
            if(!root || !root.querySelectorAll) return;
            var nodes = root.querySelectorAll('*');
            for(var i=0;i<nodes.length;i++){
              var el = nodes[i];
              if(el && !el.__lykkeHidden && isBadge(el)){ el.__lykkeHidden = true; hide(el); }
            }
          }

          // ===== 系统自动填充 / 密码保险箱防御 =====
          function antiAutofill(root){
            var scope = (root && root.querySelectorAll) ? root : document;
            var inputs = scope.querySelectorAll ? scope.querySelectorAll('input') : [];
            for(var i=0;i<inputs.length;i++){
              var el = inputs[i];
              el.setAttribute('autocomplete', el.type === 'password' ? 'new-password' : 'off');
              el.setAttribute('data-form-type','other');
            }
          }
          // 用兼容 React 的原生 setter 写值并派发 input 事件，确保受控组件状态同步
          function setNativeValue(el, value){
            try{
              var proto = Object.getPrototypeOf(el);
              var d = Object.getOwnPropertyDescriptor(proto, 'value');
              if(d && d.set) d.set.call(el, value);
              else el.value = value;
              el.dispatchEvent(new Event('input', {bubbles:true}));
            }catch(e){ el.value = value; }
          }

          function run(){ if(document.body){ scan(document.body); antiAutofill(document); } }
          if(document.readyState === 'loading'){
            document.addEventListener('DOMContentLoaded', run);
          } else { run(); }
          window.addEventListener('load', run);

          // 节流的 DOM 监听：DOM 变化再频繁，最多每 800ms 扫描一次
          try{
            var pending = false;
            var mo = new MutationObserver(function(){
              if(pending) return;
              pending = true;
              setTimeout(function(){ pending = false; run(); }, 800);
            });
            mo.observe(document.documentElement, {childList:true, subtree:true});
          }catch(e){}
          setInterval(run, 8000);

          // 聚焦任一输入框时，保护其他输入框的值不被自动填充清空
          document.addEventListener('focusin', function(e){
            var t = e.target;
            if(!t || t.tagName !== 'INPUT') return;
            var inputs = document.querySelectorAll('input');
            for(var i=0;i<inputs.length;i++){
              if(inputs[i] !== t) inputs[i].__lykkeSaved = inputs[i].value;
            }
            var restore = function(){
              for(var j=0;j<inputs.length;j++){
                var x = inputs[j];
                if(x !== t && x.__lykkeSaved && !x.value){
                  setNativeValue(x, x.__lykkeSaved);
                }
              }
            };
            try{
              requestAnimationFrame(function(){ requestAnimationFrame(restore); });
            }catch(e){}
            setTimeout(restore, 50);
            setTimeout(restore, 160);
          }, true);
          antiAutofill(document);
        })();
        """;

    private void inject() {
        if (this.getBridge() == null) return;
        WebView wv = this.getBridge().getWebView();
        if (wv == null) return;
        wv.evaluateJavascript(INJECT_JS, null);
    }

    private void setupFocus() {
        if (this.getBridge() == null) return;
        WebView wv = this.getBridge().getWebView();
        if (wv == null) return;
        // 保证 WebView 可聚焦，不拦截触摸、不抢焦点
        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
        // 恢复 GPU 硬件加速渲染，提升滑动/动画/粒子帧率；自动填充清空问题由下方
        // setImportantForAutofill 与注入脚本的值保护承担。
        // 若真机复现输入框被自动填充清空，可回退为 View.LAYER_TYPE_SOFTWARE。
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 禁用系统自动填充 / 密码保险箱（鸿蒙上密码框聚焦会触发并清空输入框）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        WebView wv = this.getBridge() != null ? this.getBridge().getWebView() : null;
        if (wv != null) {
            setupFocus();
            if (updateManager == null) updateManager = new UpdateManager(this);
            // 从「安装未知来源」设置页返回后，若已授权则继续安装待处理的包
            updateManager.onReturnFromSettings();
            // 页面每次加载完成都重新注入，保证水印隐藏与值保护生效
            if (!listenerRegistered && getBridge() != null) {
                listenerRegistered = true;
                getBridge().addWebViewListener(new WebViewListener() {
                    @Override
                    public void onPageLoaded(WebView view) {
                        view.postDelayed(MainActivity.this::inject, 200);
                        view.postDelayed(MainActivity.this::inject, 900);
                    }
                });
            }
            // 兜底注入
            wv.postDelayed(this::inject, 1200);
            wv.postDelayed(this::inject, 3500);
            wv.postDelayed(this::inject, 7000);
            if (!updateChecked) {
                updateChecked = true;
                wv.postDelayed(() -> updateManager.check(), 5000);
            }
        }
    }
}
