package cn.classbell.app

import android.net.Uri

object LocationMapPage {
    // Initial camera view only; never treated as a chosen point.
    val initialView=ManualLocation(40.1396870,116.2315696)
    fun allowedResource(uri:Uri)=uri.scheme=="https" && uri.host=="tile.openstreetmap.org" && uri.port in listOf(-1,443) && uri.userInfo==null && Regex("/[0-9]{1,2}/[0-9]+/[0-9]+\\.png").matches(uri.path.orEmpty())
    fun picked(uri:Uri):ManualLocation?=diagnosticRunCatching{
        if(uri.scheme!="cbmap" || uri.host!="pick" || uri.userInfo!=null || uri.port!=-1 || !uri.path.isNullOrEmpty() || uri.fragment!=null || uri.queryParameterNames!=setOf("lat","lon") || uri.getQueryParameters("lat").size!=1 || uri.getQueryParameters("lon").size!=1)return null
        ManualLocation.parse(uri.getQueryParameter("lat").orEmpty(),uri.getQueryParameter("lon").orEmpty(),"")
    }.getOrNull()
    fun selectionScript(point:ManualLocation,center:Boolean=true):String {
        point.requireValid();return "window.selectPoint&&window.selectPoint(${point.latitude},${point.longitude},$center);"
    }
    fun html(css:String,js:String,point:ManualLocation?):String {
        point?.requireValid();val camera=point ?: initialView
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"><style>
            $css html,body,#map{height:100%;margin:0}body{background:#e4eee8;font:14px sans-serif}.pin{width:20px;height:20px;border:3px solid white;background:#146b53;border-radius:50%;box-shadow:0 2px 6px #43665288;box-sizing:border-box}
            #hint{position:absolute;top:12px;left:50px;right:12px;background:white;padding:8px;border-radius:8px;color:#084b36;z-index:1000;pointer-events:none;font-size:12px}
            </style></head><body><div id="map"></div><div id="hint">正在加载地图…</div><script>$js</script><script>
            var hint=document.getElementById('hint'),marker=null;
            function notify(type,query){window.location.href='cbmap://'+type+(query?'?'+query:'');}
            var map=L.map('map',{maxZoom:19}).setView([${camera.latitude},${camera.longitude}],${if(point==null)15 else 17});
            var tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,keepBuffer:0,updateWhenIdle:true,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}).addTo(map);
            tiles.on('tileerror',function(){hint.textContent='底图未能加载，仍可用手机定位或填写坐标';notify('error');});
            tiles.on('load',function(){hint.textContent='点地图或拖动标记调整位置';notify('ready');});
            function pick(p){window.selectPoint(p.lat,p.lng,false);hint.textContent='位置已调整，请在下方确认';notify('pick','lat='+encodeURIComponent(p.lat)+'&lon='+encodeURIComponent(p.lng));}
            window.selectPoint=function(lat,lon,center){
                if(!marker){marker=L.marker([lat,lon],{draggable:true,icon:L.divIcon({className:'',html:'<div class="pin"></div>',iconSize:[20,20],iconAnchor:[10,10]})}).addTo(map);marker.on('dragend',function(){pick(marker.getLatLng());});}
                else marker.setLatLng([lat,lon]);if(center)map.setView([lat,lon],17);
            };
            window.centerCampus=function(){map.setView([${initialView.latitude},${initialView.longitude}],15);};
            map.on('click',function(e){pick(e.latlng);});
            ${point?.let{selectionScript(it,false)} ?: ""}
            </script></body></html>""".trimIndent()
    }
}
