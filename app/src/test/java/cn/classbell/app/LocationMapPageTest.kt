package cn.classbell.app

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class LocationMapPageTest {
    @Test fun mapDocumentUsesTheSelectedPointOrCampusWithoutCommittingAnyAttendance(){
        val initial=LocationMapPage.html("","",null)
        val picked=LocationMapPage.html("","",ManualLocation(1.3,103.8))
        assertTrue(initial.contains("[40.139687,116.2315696]"));assertTrue(picked.contains("[1.3,103.8]"))
        val cwd=java.io.File(System.getProperty("user.dir"));val project=if(java.io.File(cwd,"app/src").exists())cwd else cwd.parentFile
        java.io.File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}/map-script-fixture.html").apply{parentFile.mkdirs();writeText(initial)}
    }
    @Test fun callbackRejectsMalformedAndDuplicateCoordinates(){
        assertEquals(ManualLocation(39.9,116.4),LocationMapPage.picked(Uri.parse("cbmap://pick?lat=39.9&lon=116.4")))
        listOf("cbmap://pick?lat=91&lon=116","cbmap://pick?lat=NaN&lon=116","cbmap://pick?lat=39&lat=40&lon=116","cbmap://pick?lat=39&lon=116&unknown=x","https://pick?lat=39&lon=116","cbmap://pick/extra?lat=39&lon=116").forEach{assertNull(LocationMapPage.picked(Uri.parse(it)))}
    }
    @Test fun resourceAccessIsRestrictedToTilesFromTheOfficialDomain(){
        assertTrue(LocationMapPage.allowedResource(Uri.parse("https://tile.openstreetmap.org/16/50000/24000.png")))
        listOf("http://tile.openstreetmap.org/1/1/1.png","https://tile.openstreetmap.org.evil.example/1/1/1.png","file:///private","https://evil.example/1/1/1.png","https://name@tile.openstreetmap.org/1/1/1.png","https://tile.openstreetmap.org:444/1/1/1.png","https://tile.openstreetmap.org/unexpected").forEach{assertFalse(LocationMapPage.allowedResource(Uri.parse(it)))}
    }
}
