package com.musicone.demo

/** 接收腾讯验证结果；官方容器收起时交回服务器续接原请求。 */
internal val qqCaptchaProofListener = """
    (function() {
      if (window.__musicOneProofListener) return;
      window.__musicOneProofListener = true;
      var challengeSeen = false;
      var closeTimer = 0;
      var resultSent = false;

      function closeHost() {
        if (resultSent) return;
        resultSent = true;
        MusicOneVerification.close();
      }

      function isVisible(node) {
        if (!node) return false;
        var style = window.getComputedStyle(node);
        var rect = node.getBoundingClientRect();
        return style.display !== 'none' && style.visibility !== 'hidden' &&
          Number(style.opacity || 1) > 0.01 && rect.width > 80 && rect.height > 80;
      }

      function hasVisibleChallenge() {
        var nodes = document.querySelectorAll(
          'iframe[src*="captcha" i],iframe[src*="turing" i],iframe[src*="verify" i],' +
          '[id*="captcha" i],[class*="captcha" i],[id*="verify" i],[class*="verify" i]'
        );
        for (var i = 0; i < nodes.length; i++) {
          if (isVisible(nodes[i])) return true;
        }
        return false;
      }

      function inspectChallenge() {
        var visible = hasVisibleChallenge();
        if (visible) {
          challengeSeen = true;
          if (closeTimer) window.clearTimeout(closeTimer);
          closeTimer = 0;
        } else if (challengeSeen && !resultSent && !closeTimer) {
          closeTimer = window.setTimeout(function() {
            closeTimer = 0;
            if (!hasVisibleChallenge()) closeHost();
          }, 360);
        }
      }

      window.close = closeHost;
      window.addEventListener('message', function(event) {
        if (!/^https:\/\/([a-z0-9-]+\.)*(captcha\.qcloud\.com|captcha\.qq\.com)$/i.test(event.origin)) return;
        var data = event.data;
        try { if (typeof data === 'string') data = JSON.parse(data); } catch(e) { return; }
        if (data && data.data && typeof data.data === 'object') data = data.data;
        if (!data || typeof data.ret === 'undefined') return;
        if (Number(data.ret) === 0 && data.ticket && data.randstr) {
          resultSent = true;
          MusicOneVerification.complete(JSON.stringify(data));
        } else if (Number(data.ret) !== 0) {
          closeHost();
        }
      });
      new MutationObserver(inspectChallenge).observe(document.documentElement, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['class', 'style', 'hidden']
      });
      inspectChallenge();
      window.setInterval(inspectChallenge, 400);
    })();
""".trimIndent()
