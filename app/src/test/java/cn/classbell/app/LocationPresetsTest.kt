package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test

class LocationPresetsTest {
    @Test fun oldPresetsRemainReadableAndIntentResultDoesNotChangeCoordinates(){
        val data=mutableMapOf<String,String>();val store=LocationPresets({data[it]},{k,v->data[k]=v},{data.remove(it);Unit})
        data["location.preset.a"]="""{"owner":"a","latitude":39.9,"longitude":116.4,"address":"旧位置"}"""
        assertEquals(ManualLocation(39.9,116.4,"旧位置"),store.get("a"))
    }
    @Test fun presetsStayWithTheirOwnerAndMalformedCoordinatesNeverLoad() {
        val data=mutableMapOf<String,String>();val store=LocationPresets({data[it]},{k,v->data[k]=v},{data.remove(it);Unit})
        val a=ManualLocation(1.3,103.8,"演示地点");val b=ManualLocation(-33.8,151.2,"另一个演示地点")
        store.save("account-a",a);store.save("account-b",b)
        assertEquals(a,store.get("account-a"));assertEquals(b,store.get("account-b"))
        store.clear("account-a");assertNull(store.get("account-a"));assertEquals(b,store.get("account-b"))
        data["location.preset.account-a"]=data.getValue("location.preset.account-b")
        assertNull(store.get("account-a"))
        assertThrows(ApiProblem::class.java){store.save("account-a",ManualLocation(Double.NaN,103.8))}
        assertThrows(ApiProblem::class.java){ManualLocation.parse("91","103","invalid")}
        assertThrows(ApiProblem::class.java){ManualLocation.parse("1","181","invalid")}
        assertThrows(ApiProblem::class.java){ManualLocation.parse("1","103","x\ny")}
    }
}
