package com.musicone.demo

import org.json.JSONObject

/** 对照 PiliPlus 的 fullpage 与配置双加载流程，均就绪后只初始化一次。 */
internal fun buildBiliGeetestHtml(gt: String, challenge: String): String {
    val gtJson = JSONObject.quote(gt).replace("<", "\\u003c")
    val challengeJson = JSONObject.quote(challenge).replace("<", "\\u003c")
    return """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>html,body{margin:0;width:100%;height:100%;background:transparent}#msg{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;font:14px sans-serif;color:#888}</style>
        </head><body><div id="msg">正在加载安全验证…</div>
        <script>
        var config=null,configReceived=false,scriptReady=false,started=false,finished=false,ready=false;
        // 极验仅接受约定格式的 JSONP 回调名，注册名与请求参数必须一致。
        var configCallback='geetest_'+Date.now();
        function report(type,data){if(window.AndroidGeetest)window.AndroidGeetest.postMessage(type,JSON.stringify(data||{}));}
        function fail(stage){if(finished)return;finished=true;report('error',{stage:stage});}
        var loadingTimer=setTimeout(function(){if(!ready)fail('TIMEOUT');},20000);
        window.addEventListener('error',function(event){if(event.message)fail('INITIALIZE');});
        function start(){
            if(!config||!scriptReady||started||finished)return;
            started=true;
            try{
                var captcha=Geetest(config);
                captcha.onSuccess(function(){if(finished)return;finished=true;report('success',captcha.getValidate());});
                captcha.onClose(function(){if(finished)return;finished=true;report('close',{});});
                captcha.onError(function(){fail('VERIFY');});
                captcha.onReady(function(){
                    if(finished)return;
                    ready=true;clearTimeout(loadingTimer);
                    document.getElementById('msg').style.display='none';
                    report('ready',{});captcha.verify();
                });
            }catch(error){fail('INITIALIZE');}
        }
        window[configCallback]=function(data){
            configReceived=true;
            if(finished)return;
            if(!data||data.status!=='success'||!data.data){fail('CONFIG');return;}
            config=Object.assign({},data.data,{gt:$gtJson,challenge:$challengeJson,
                offline:false,new_captcha:true,product:'bind',width:'100%',https:true,protocol:'https://'});
            start();
        };
        function loadScript(url,stage,onload){
            var script=document.createElement('script');script.src=url;
            script.onload=onload;script.onerror=function(){fail(stage);};document.head.appendChild(script);
        }
        loadScript('https://static.geetest.com/static/js/fullpage.0.0.0.js','SCRIPT',function(){scriptReady=true;start();});
        loadScript('https://api.geetest.com/gettype.php?gt='+encodeURIComponent($gtJson)+'&callback='+encodeURIComponent(configCallback),'CONFIG',function(){
            // HTTP 成功仍可能返回未调用回调的错误正文，不能一直等到总超时。
            if(!configReceived)fail('CONFIG');
        });
        </script></body></html>
    """.trimIndent()
}
