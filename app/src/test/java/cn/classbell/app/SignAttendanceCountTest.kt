package cn.classbell.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic student-detail payloads; no network, teacher endpoints, or attendance submission. */
class SignAttendanceCountTest {
    private val item=SignActivity("301","人数用例",Course("101","201","合成课程"),otherId=2)
    private fun detail()=JSONObject().put("id","301").put("activeType",2)
        .put("courseId","101").put("clazzid","201").put("otherId",2).put("status",1)
    private fun count(value:Any)=SignOverview.read(detail().put("attendNum",value),item)!!.attendedCount

    @Test fun explicitIntegerNumbersAndNumericStringsAreAcceptedWithoutTruncation() {
        listOf(85,85L,85.0,"85"," 85 ","85.000","8.5e1").forEach { value->
            assertEquals("$value",85,count(value))
        }
        assertEquals(Int.MAX_VALUE,count(Int.MAX_VALUE.toString()))
    }

    @Test fun explicitlyReportedZeroIsPreservedButMissingDataDoesNotBecomeZero() {
        listOf(0,0L,0.0,"0").forEach { assertEquals(0,count(it)) }
        assertNull(SignOverview.read(detail(),item)!!.attendedCount)
        assertNull(count(JSONObject.NULL))
        assertNull(count(""))
        assertNull(count("  "))
    }

    @Test fun fractionalNegativeOversizedAndNonNumericValuesRemainUnavailable() {
        listOf(-1,"-1",85.5,"85.5","NaN","Infinity","85人","2147483648",
            "1e100",true,false,JSONObject().put("value",85),JSONArray().put(85)).forEach { value->
            assertNull("$value",count(value))
        }
    }

    @Test fun casingVariantsAgreeNumericallyAndConflictsAreNotSilentlySelected() {
        val data=detail().put("attendNum","85").put("attendnum",85.0)
        assertEquals(85,SignOverview.read(data,item)!!.attendedCount)
        data.put("ATTENDNUM",86)
        assertNull(SignOverview.read(data,item)!!.attendedCount)
        data.put("ATTENDNUM","bad")
        assertNull(SignOverview.read(data,item)!!.attendedCount)
        assertEquals(7,SignOverview.read(detail().put("attendnum",7),item)!!.attendedCount)
    }

    @Test fun totalStudentCountsPercentagesAndArraysAreNotAttendanceEvidence() {
        val data=detail().put("studentcount",100).put("count",99).put("total",90)
            .put("attendPer",85).put("releaseNum",84).put("signCount",83)
            .put("activeList",JSONArray().put(JSONObject().put("attendNum",82)))
        assertNull(SignOverview.read(data,item)!!.attendedCount)
    }

    @Test fun foreignActivityCourseClassAndConflictingIdentifiersAreRejectedBeforeCount() {
        val changes=listOf("id" to "302","activeId" to "302","activeType" to 42,
            "courseId" to "102","classId" to "202","clazzid" to "202")
        changes.forEach { (key,value)->
            assertNull("$key",SignOverview.read(detail().put("attendNum",85).put(key,value),item))
        }
    }
}
