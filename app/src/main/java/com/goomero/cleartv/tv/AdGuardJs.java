package com.goomero.cleartv.tv;

/**
 * Page-side ad guard for on-demand players in the web screen, added at document start to the service's own
 * frames (YouTube's embed player, primevideo.com, paramountplus.com):
 * <ul>
 *   <li>YouTube: removes ad placements (adPlacements, playerAds, adSlots) from every player answer before the
 *   player reads it (JSON.parse, fetch's Response.json, ytInitialPlayerResponse), so no ad break is scheduled;
 *   if an ad still starts, it is skipped (skip button, or jump to the ad's end).</li>
 *   <li>Prime Video: its ads are stitched into the stream and the player does not allow seeking past them, so
 *   while the player shows its ad timer the video runs muted at 16x and returns to normal speed afterwards.</li>
 *   <li>Paramount+: the same for its stitched (server-side) ad breaks, detected by the player's ad layer.</li>
 * </ul>
 * Reports go to the blocker's bridge (counted in Settings > Ad blocking).
 */
final class AdGuardJs {
    private AdGuardJs() {}

    static String script(String bridge) {
        return "(function(){if(window.__ctvAd)return;window.__ctvAd=1;var B=window['" + bridge + "'];"
                + "function rep(k){try{if(B&&B.ad)B.ad(k);}catch(e){}}"
                + "var h=location.hostname,yt=/(^|\\.)youtube(-nocookie)?\\.com$/.test(h);"
                + "if(yt){var K=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];"
                + "var strip=function(o){try{if(o&&typeof o==='object'){var n=0,xs=[o,o.playerResponse,o.response];"
                + "for(var i=0;i<xs.length;i++){var x=xs[i];if(!x||typeof x!=='object')continue;"
                + "for(var j=0;j<K.length;j++){if(K[j] in x){delete x[K[j]];n++;}}}"
                + "if(n)rep('youtube: ad placements removed');}}catch(e){}return o;};"
                + "var P=JSON.parse;JSON.parse=function(){return strip(P.apply(this,arguments));};"
                + "if(window.Response){var RJ=Response.prototype.json;Response.prototype.json=function(){return RJ.apply(this,arguments).then(strip);};}"
                + "var _y;try{Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,"
                + "get:function(){return _y;},set:function(v){_y=strip(v);}});}catch(e){}"
                + "var inAd=false;setInterval(function(){var p=document.querySelector('.html5-video-player');"
                + "var ad=p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting'));"
                + "if(!ad){inAd=false;return;}"
                + "var s=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,[class*=ytp-ad-skip]');"
                + "if(s)s.click();var v=p.querySelector('video');"
                + "if(v&&isFinite(v.duration)&&v.duration>0){v.muted=true;v.currentTime=v.duration;}"
                + "if(!inAd){inAd=true;rep('youtube: ad skipped');}},250);return;}"
                + "if(/(^|\\.)primevideo\\.com$/.test(h)){var on=false,wasMuted=false;"
                + "var big=function(){var b=null,a=-1,l=document.querySelectorAll('video');for(var i=0;i<l.length;i++){"
                + "var r=l[i].getBoundingClientRect(),s=r.width*r.height;if(s>a){a=s;b=l[i];}}return b;};"
                + "setInterval(function(){var t=document.querySelector('.atvwebplayersdk-ad-timer,[class*=atvwebplayersdk-ad-timer],"
                + "[class*=adtimeindicator],[class*=ad-timer-text]');"
                + "var vis=t&&t.getBoundingClientRect().width>0;var v=big();if(!v)return;"
                + "if(vis){if(!on){on=true;wasMuted=v.muted;rep('prime: ad break fast-forwarded');}"
                + "v.muted=true;if(v.playbackRate!==16){try{v.playbackRate=16;}catch(e){}}}"
                + "else if(on){on=false;try{v.playbackRate=1;}catch(e){}v.muted=wasMuted;}},250);}"
                // Paramount+: Essential plans get ad breaks stitched into the stream (Google DAI) and its player
                // refuses to seek while one plays; its ad layer (data-role=adContainer) is shown only during a
                // break, and then the video runs muted at 16x like Prime's
                + "if(/(^|\\.)paramountplus\\.com$/.test(h)){var pon=false,pMuted=false,pv=null;"
                + "var pbig=function(){var b=null,a=-1,l=document.querySelectorAll('video');for(var i=0;i<l.length;i++){"
                + "var r=l[i].getBoundingClientRect(),s=r.width*r.height;if(s>a){a=s;b=l[i];}}return b;};"
                + "setInterval(function(){var c=document.querySelector('[data-role=adContainer]');"
                + "var vis=false;if(c){var cs=getComputedStyle(c);vis=cs.display!=='none'&&cs.visibility!=='hidden'&&c.getBoundingClientRect().width>0;}"
                + "var v=pbig();if(!v)return;"
                + "if(vis){if(!pon){pon=true;pv=v;pMuted=v.muted;rep('paramount: ad break fast-forwarded');}"
                + "v.muted=true;if(v.playbackRate!==16){try{v.playbackRate=16;}catch(e){}}}"
                + "else if(pon){pon=false;var w=pv||v;try{w.playbackRate=1;}catch(e){}w.muted=pMuted;if(w!==v){try{v.playbackRate=1;}catch(e){}}}},250);}"
                + "})();";
    }
}
