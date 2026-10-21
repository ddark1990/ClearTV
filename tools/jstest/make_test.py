#!/usr/bin/env python3
"""Build test.html: content.js with a mock bridge plus a page that checks scriptlets and element hiding.
Run: python3 make_test.py && google-chrome --headless=new --disable-gpu --virtual-time-budget=3000 --dump-dom test.html"""
import json, os
here = os.path.dirname(os.path.abspath(__file__))
js = open(os.path.join(here, '../../app/src/main/assets/js/content.js')).read()
js = js.replace('__CTV_BRIDGE__', '__CTV_TEST').replace('__CTV_TOKEN__', 'tok')
snapshot = {
    "on": 1,
    "hide": [".ad-banner", "#sponsor", "div[data-ad]", "bad:::selector", ".x:has-text(foo)"],
    "styles": [["#styled", "visibility: hidden !important"]],
    "sl": [
        ["set-constant", "ytInitialPlayerResponse.adPlacements", "undefined"],
        ["set-constant", "cfgFlag", "true"],
        ["json-prune", "playerResponse.adPlacements playerResponse.playerAds", ""],
        ["abort-on-property-read", "adsTracker"],
        ["no-setTimeout-if", "adblock-detect"],
        ["addEventListener-defuser", "load", "popunder"],
        ["no-window-open-if", ""],
        ["remove-attr", "onclick", "a.track"],
        ["set-local-storage-item", "consent", "true"],
        ["unknown-scriptlet", "x"]
    ],
    "lg": 1
}
mock = """
window.__CTV_TEST = {
  snapshot: function(u){ return %s; },
  generic: function(u, toks){ var t = JSON.parse(toks); var out = [];
     if (t.indexOf('.adsbox') >= 0) out.push('.adsbox');
     if (t.indexOf('#late-ad') >= 0) out.push('#late-ad');
     window.__tokensSeen = (window.__tokensSeen||0) + t.length; return JSON.stringify(out); },
  media: function(id, p){ window.__media = p; }
};
""" % json.dumps(json.dumps(snapshot))
page = """<!DOCTYPE html><html><head><script>%s</script><script>%s</script>
<script>
window.ytInitialPlayerResponse = {adPlacements:[1,2], videoDetails:{id:'x'}};
var adsHit = 'not-thrown';
try { var z = window.adsTracker; adsHit = 'read-ok'; } catch(e) { adsHit = 'thrown'; }
var timerRan = false; setTimeout(function(){ timerRan = 'adblock-detect ran'; /* adblock-detect */ }, 10);
var otherTimer = false; setTimeout(function(){ otherTimer = true; }, 10);
var popRan = false; window.addEventListener('load', function popunder(){ popRan = true; });
var pr = JSON.parse('{"playerResponse":{"adPlacements":[1],"playerAds":[2],"keep":3}}');
var win = window.open('https://ads.example/pop');
</script></head><body>
<div class="ad-banner" id="a1">ad</div><div id="sponsor">s</div><div data-ad="1" id="a3">d</div>
<div class="adsbox" id="a4">generic</div><div id="styled">styled</div><div id="content">content</div>
<a class="track" onclick="x()" id="lnk">l</a>
<pre id="out"></pre>
<script>
setTimeout(function(){
  var late = document.createElement('div'); late.id = 'late-ad'; late.textContent='late'; document.body.appendChild(late);
}, 50);
setTimeout(function(){
  function vis(id){ var e = document.getElementById(id); return getComputedStyle(e).display !== 'none' && getComputedStyle(e).visibility !== 'hidden'; }
  var r = {
    setConstant: window.ytInitialPlayerResponse.adPlacements === undefined && window.ytInitialPlayerResponse.videoDetails.id === 'x',
    setConstantTop: window.cfgFlag === true,
    jsonPrune: pr.playerResponse.adPlacements === undefined && pr.playerResponse.playerAds === undefined && pr.playerResponse.keep === 3,
    abortOnRead: adsHit === 'thrown',
    noSetTimeout: timerRan === false && otherTimer === true,
    aeldDefused: popRan === false,
    windowOpenBlocked: win === null,
    removeAttr: !document.getElementById('lnk').hasAttribute('onclick'),
    localStorage: (function(){ try { return localStorage.getItem('consent') === 'true'; } catch(e) { return 'n/a (file://)'; } })(),
    hideClass: !vis('a1'), hideId: !vis('sponsor'), hideAttr: !vis('a3'),
    lowGeneric: !vis('a4'), lowGenericLate: !vis('late-ad'), style: !vis('styled'), contentVisible: vis('content')
  };
  document.getElementById('out').textContent = 'RESULT ' + JSON.stringify(r);
}, 600);
</script></body></html>""" % (mock, js)
open(os.path.join(here, 'test.html'), 'w').write(page)
print('wrote test.html')
