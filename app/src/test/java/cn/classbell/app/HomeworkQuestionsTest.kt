package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test

class HomeworkQuestionsTest {
    private val base="https://mooc1-api.chaoxing.com/mooc-ans/work/phone/selectWorkQuestion?enc=private"
    private fun q(stem:String,options:String="",type:String="单选题")="<div class='questionLi singleQuesId' typename='$type'><h3 class='mark_name'>$stem</h3>$options</div>"
    private fun parse(html:String)=HomeworkQuestionParser.parse(html,base)
    @Test fun tenChoiceQuestionsAndOtherTypesAreCopiedInOrderWithoutSavedAnswersOrAttachments() {
        val choices=(1..10).joinToString(""){i->q("合成选择题$i", "<div class=answerBg><span class=num_option>A.</span><div class=answer_p>选项甲$i</div></div><div class=answerBg><span class=num_option>B.</span><div class=answer_p>选项乙$i</div></div>")}
        val rest=q("填空题干", "<div class=stem_answer><div class=Answer><textarea>private-filled-answer</textarea></div><div class=Answer><input value='private-other'></div></div>","填空题")+
            q("合成判断题", "<div class=answerBg><span class=answer_p>正确</span></div><div class=answerBg><span class=answer_p>错误</span></div>","判断题")+
            q("简答正文 <img src='https://p.ananas.chaoxing.com/image.jpg'><a href='report.pdf'><img src='https://p.ananas.chaoxing.com/document-icon.png'>附件</a><iframe module='insertAttach' src='private.docx'></iframe>","<div class=analysis>private-analysis</div>","简答题")
        val set=parse("<nav>private-navigation</nav>$choices$rest<script>private-script</script>")
        val text=set.exportText("合成作业","合成课程")
        assertEquals(13,set.questions.size);assertEquals(1,set.imageUrls.size)
        assertTrue(text.indexOf("合成选择题1")<text.indexOf("合成选择题10"))
        for(i in 1..10){assertTrue(text.contains("选项甲$i"));assertTrue(text.contains("选项乙$i"))}
        assertTrue(text.contains("共 2 个填空位置"));assertTrue(text.contains("正确"));assertTrue(text.contains("简答正文"))
        assertFalse(text.contains("private-"));assertFalse(text.contains("report.pdf"));assertFalse(text.contains("document-icon"))
    }
    @Test fun allAndSingleCopiesNumberTheirOwnUniqueImagesWithoutCopyingSignedUrls() {
        val set=parse(q("第一题<img src='https://p.ananas.chaoxing.com/one.png?enc=secret'>")+q("第二题<img data-src='https://p.ananas.chaoxing.com/two.png'><img src='https://p.ananas.chaoxing.com/one.png?enc=secret'>"))
        val all=set.exportText("","".trim())
        assertEquals(2,set.imageUrls.size);assertTrue(all.contains("[图片 2]"));assertFalse(all.contains("secret"));assertFalse(all.contains("https:"))
        val single=set.exportText("","",set.questions[1])
        assertTrue(single.contains("第二题[图片 1][图片 2]"));assertFalse(single.contains("第一题"))
    }
    @Test fun lazyImagesAreResolvedAndUntrustedSourcesReportedWithoutFetching() {
        val set=parse(q("<img src='http://p.ananas.chaoxing.com/real.png'><img src='https://evil.invalid/a.png'><img src='file:///secret'><img src='javascript:alert(1)'>"))
        assertEquals(listOf("https://p.ananas.chaoxing.com/real.png"),set.imageUrls)
        assertEquals(3,set.unsupportedImages)
        assertTrue(set.exportText("","").contains("图片来源暂不支持"))
    }
    @Test fun groupedQuestionsKeepSharedStemAndDoNotMergeAllOptionsIntoOneQuestion() {
        val set=parse("<div class=questionLi><h3 class=mark_name>共同材料<img src='https://p.ananas.chaoxing.com/shared.jpg'></h3>"+q("子题一")+q("子题二")+"</div>")
        assertEquals(2,set.questions.size);assertEquals(1,set.imageUrls.size)
        assertTrue(set.questions.all{set.exportText("","",it).contains("共同材料")})
        assertFalse(set.exportText("","",set.questions[0]).contains("子题二"))
    }
    @Test fun texSuperscriptsTablesAndCodeArePreservedWhileControlsAndScriptAreExcluded() {
        val set=parse(q("x<sup>2</sup> + y<sub>1</sub><script type='math/tex'>\\frac{a}{b}</script><table><tr><td>甲</td><td>乙</td></tr></table><pre>if (x) {\n  y();\n}</pre><input type=text value='secret'><script>private-run</script>"))
        val text=set.exportText("","")
        assertTrue(text.contains("x^(2)"));assertTrue(text.contains("y_(1)"));assertTrue(text.contains("\\frac{a}{b}"))
        assertTrue(text.contains("甲 | 乙"));assertTrue(text.contains("y();"));assertTrue(text.contains("____"))
        assertFalse(text.contains("secret"));assertFalse(text.contains("private-run"))
    }
    @Test fun unrecognizedPagesAndMissingStemsDoNotBecomeWholePageCopy() {
        assertTrue(parse("<h1>课程菜单</h1><img src='https://p.ananas.chaoxing.com/avatar.jpg'>").questions.isEmpty())
        val set=parse("<div class=questionLi><textarea>secret-answer</textarea></div>"+q("正常题目"))
        assertEquals(1,set.skipped);assertEquals(1,set.questions.size);assertFalse(set.exportText("","").contains("secret"))
    }
    @Test fun inlineCommonImageIsKeptButSvgAndForeignCredentialUrlsAreNotAccepted() {
        val inline="data:image/png;base64,iVBORw0KGgo="
        val set=parse(q("<img src='$inline'><img src='data:image/svg+xml;base64,PHN2Zz4='><img src='https://user:password@p.ananas.chaoxing.com/a.png'>"))
        assertEquals(listOf(inline),set.imageUrls);assertEquals(2,set.unsupportedImages)
        assertFalse(set.exportText("","").contains("iVBOR"))
    }
    @Test fun oversizedPagesAndTooManyQuestionsFailClearlyInsteadOfTruncating() {
        assertThrows(ApiProblem::class.java){parse("a".repeat(2*1024*1024+1))}
        assertThrows(ApiProblem::class.java){parse((1..301).joinToString(""){q("题目$it")})}
    }
    @Test fun realPhoneShapeKeepsAllOptionsAndOnlyQuestionImagesNotAnswerIconsOrSubmittedImages() {
        val set=parse("""
            <div class='singleQuesId'><div class=stemDiv><span class=stemGray>(单选题，4分)</span>
            <div class=qtContent>手机选择题<img src='https://p.ananas.chaoxing.com/stem.png'></div></div>
            <div class=optionDiv><div class=optionCon><span class=wrIcon><img src='https://p.ananas.chaoxing.com/private-icon.png'></span>
            <div class=qtDetail>A.图文选项<img src='https://p.ananas.chaoxing.com/option.png'></div></div>
            <div class=optionCon><div class=qtDetail>B.第二选项</div></div></div>
            <div class=answerDiv>private-answer<img src='https://p.ananas.chaoxing.com/private-answer.png'></div></div>
            <div class='singleQuesId'><div class=stemDiv><span class=stemGray>(简答题，50分)</span><div class=qtContent>手机简答正文</div></div>
            <div class=stuAnswerContent>private-submitted<img src='https://p.ananas.chaoxing.com/private-submitted.png'></div>
            <div class='true_answer'><div class=rightAnswerContent>private-reference</div></div></div>
        """.trimIndent())
        assertEquals(2,set.questions.size)
        assertEquals(listOf("单选题","简答题"),set.questions.map{it.kind})
        assertEquals(listOf("https://p.ananas.chaoxing.com/stem.png","https://p.ananas.chaoxing.com/option.png"),set.imageUrls)
        val text=set.exportText("","")
        assertTrue(text.contains("A. 图文选项"));assertTrue(text.contains("B. 第二选项"))
        assertFalse(text.contains("A. A."));assertFalse(text.contains("private-"))
    }
    @Test fun phoneFillAndJudgmentStemsDoNotCopyTheirFilledAnswerAreas() {
        val set=parse("""<div class=singleQuesId><span class=stemGray>填空题</span><div class=qtContent>手机填空____。</div><div class=stuAnswerContent>private-filled</div></div>
            <div class=singleQuesId><span class=stemGray>判断题</span><div class=qtContent>手机判断题干。</div><div class=optionCon><div class=qtDetail>A.正确</div></div><div class=optionCon><div class=qtDetail>B.错误</div></div></div>""")
        assertEquals(listOf("填空题","判断题"),set.questions.map{it.kind})
        assertTrue(set.exportText("","").contains("正确"));assertFalse(set.exportText("","").contains("private-filled"))
    }
    @Test fun mixedPhoneAndDesktopRootClassesAreNotDuplicatedAndKeepSharedMaterial() {
        val set=parse("<div class='singleQuesId'><div class=qtContent>共同手机材料</div>"+
            "<div class='singleQuesId questionLi'><div class=qtContent>子题甲</div></div>"+
            "<div class=singleQuesId><div class=qtContent>子题乙</div></div></div>")
        assertEquals(2,set.questions.size)
        assertTrue(set.questions.all{set.exportText("","",it).contains("共同手机材料")})
        assertFalse(set.exportText("","",set.questions[0]).contains("子题乙"))
    }
    @Test fun unsubmittedPhoneChoiceKeepsAllFourOptionTextsAndTheirImages() {
        val set=parse("""<div class=singleQuesId><h2 class=titType>1. 单选题</h2><div class=workWrap>分页单选题干</div>
            <div class=Answer><span class='choose checked' orichoice=A aria-label='已选择'>A</span><div class='centerSpan workWrap' aria-hidden=true>甲<img src='https://p.ananas.chaoxing.com/option.png'></div></div>
            <div class=Answer><span class=choose orichoice=B>B</span><div class='centerSpan workWrap' aria-hidden=true>乙</div></div>
            <div class=Answer><span class=choose orichoice=C>C</span><div class='centerSpan workWrap' aria-hidden=true>丙</div></div>
            <div class=Answer><span class=choose orichoice=D>D</span><div class='centerSpan workWrap' aria-hidden=true>丁</div></div>
            <input type=hidden name=answer1 value=private-filled></div>""")
        val text=set.exportText("","")
        for(value in listOf("A. 甲","B. 乙","C. 丙","D. 丁"))assertTrue(text.contains(value))
        assertEquals(1,set.imageUrls.size);assertFalse(text.contains("已选择"));assertFalse(text.contains("private-filled"))
    }
    @Test fun enteredPhoneWorkReadsOnlyWorkWrapAndLeavesDocumentsAndAnswerEditorAlone() {
        val set=parse("""<div class='wid750 singleQuesId'><form method=post>
            <input type=hidden name=enc value=private-signature><h2 class=titType>1. 其它</h2>
            <div class='ans-cc workWrap'><p>实验说明文字</p><div class=editor-iframe><iframe module=insertAttach filename='作业.pdf'></iframe></div></div>
            <div class=Briefanswer><div>private-editor</div><input name=answer123 value=private-filled><img src='https://p.ananas.chaoxing.com/private-camera.png'></div>
            </form></div>""")
        assertEquals(1,set.questions.size);assertEquals("其它题",set.questions.single().kind)
        assertTrue(set.imageUrls.isEmpty());assertEquals(0,set.unsupportedImages)
        assertTrue(set.exportText("","").contains("实验说明文字"));assertFalse(set.exportText("","").contains("private-"))
        assertFalse(set.exportText("","").contains(".pdf"))
    }
    @Test fun unsubmittedMultipleChoiceKeepsDxcheckOptionsWithoutCopyingSelectedStates() {
        val set=parse("<div class=singleQuesId><h2 class=titType>11. 多选题</h2><div class=workWrap>多选题干</div>"+
            listOf("甲","乙","丙","丁").mapIndexed{i,text->
                "<div class='clearfix Answer workTextWrap'><span class='dxcheck selected' orichoice='${'A'+i}' aria-label='已选中'></span><div class='centerSpan workWrap' aria-hidden=true>$text</div></div>"
            }.joinToString("")+"<div class=stuAnswerContent>private-filled</div></div>")
        assertEquals("多选题",set.questions.single().kind)
        val text=set.exportText("","")
        for(option in listOf("A. 甲","B. 乙","C. 丙","D. 丁"))assertTrue(text.contains(option))
        assertFalse(text.contains("已选中"));assertFalse(text.contains("private-filled"))
    }
    @Test fun anExplicitBlankChoiceStillKeepsItsVisibleLetter() {
        val set=parse("<div class=singleQuesId><h2 class=titType>单选题</h2><div class=workWrap>空白选项测试</div>"+
            "<div class=Answer><span class=choose orichoice=A>A</span><div class=centerSpan>甲</div></div>"+
            "<div class=Answer><span class=choose orichoice=E>E</span><div class=centerSpan><br></div></div></div>")
        assertTrue(set.exportText("","").endsWith("E."))
        assertTrue(set.exportText("","").contains("A. 甲"))
    }
    @Test fun mathMlFractionsPowersAndRootsKeepReadableStructure() {
        val set=parse(q("<math><mfrac><msup><mi>x</mi><mn>2</mn></msup><msqrt><mi>y</mi></msqrt></mfrac></math>"))
        assertTrue(set.exportText("","").contains("(x^(2))/(sqrt(y))"))
    }
}
