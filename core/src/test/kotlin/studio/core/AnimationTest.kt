package studio.core

import studio.core.animation.*
import studio.core.geometry.*
import studio.core.json.*
import studio.core.util.EditException
import studio.core.util.Num
import kotlin.math.abs
import kotlin.test.*

class MolangTest {
    private fun ev(src: String, setup: MolangContext.() -> Unit = {}): Double {
        val ctx = MolangContext().apply(setup)
        return Molang.parse(src).eval(ctx)
    }

    @Test fun arithmeticPrecedenceAndUnary() {
        assertEquals(7.0, ev("1 + 2 * 3"))
        assertEquals(9.0, ev("(1 + 2) * 3"))
        assertEquals(-3.0, ev("-(1 + 2)"))
        assertEquals(0.0, ev("5 / 0")) // divisão por zero dá 0
        assertEquals(1.0, ev("!0"))
        assertEquals(0.5, ev(".5"))
    }

    @Test fun comparisonLogicTernaryAndCoalesce() {
        assertEquals(1.0, ev("1 < 2 && 3 >= 3"))
        assertEquals(0.0, ev("1 == 2 || 0"))
        assertEquals(10.0, ev("1 > 0 ? 10 : 20"))
        assertEquals(20.0, ev("1 < 0 ? 10 : 20"))
        assertEquals(0.0, ev("1 < 0 ? 10"))
        assertEquals(4.0, ev("query.missing ?? 4"))
        assertEquals(2.0, ev("query.x ?? 4") { queries["x"] = 2.0 })
    }

    @Test fun mathFunctionsUseDegrees() {
        assertTrue(abs(ev("math.sin(90)") - 1.0) < 1e-9)
        assertTrue(abs(ev("math.cos(180)") + 1.0) < 1e-9)
        assertTrue(abs(ev("math.atan2(1, 1)") - 45.0) < 1e-9)
        assertEquals(3.0, ev("math.clamp(5, 0, 3)"))
        assertEquals(5.0, ev("math.lerp(0, 10, 0.5)"))
        assertEquals(3.0, ev("Math.Floor(3.9)")) // sem diferenciar maiúsculas
        assertEquals(-3.0, ev("math.trunc(-3.9)"))
        assertEquals(-3.0, ev("math.round(-2.5) + 0.0 + 0")) // meio arredonda para longe de zero
        assertTrue(abs(ev("math.pi") - Math.PI) < 1e-12)
        assertEquals(1.0, ev("math.mod(7, 3)"))
    }

    @Test fun variablesAssignmentsAndReturn() {
        assertEquals(6.0, ev("v.x = 2; v.y = v.x * 3; return v.y;"))
        assertEquals(0.0, ev("variable.nada")) // variável nunca definida vale 0
        assertEquals(3.0, ev("t.a = 1; return t.a + 2")) // temp.
        assertEquals(1.0, ev("return 1; return 2;"))
    }

    @Test fun queriesAndUnresolvedAreReportedNotGuessed() {
        val ctx = MolangContext()
        ctx.queries["anim_time"] = 0.5
        assertEquals(1.0, Molang.parse("q.anim_time * 2").eval(ctx))
        assertEquals(0.0, Molang.parse("query.ground_speed").eval(ctx))
        assertTrue("query.ground_speed" in ctx.unresolved)
        assertEquals(setOf("anim_time"), Molang.parse("q.anim_time * 2").queryNames)
        val c2 = MolangContext()
        Molang.parse("query.is_item_name_any('slot.weapon.mainhand', 'minecraft:bow')").eval(c2)
        assertTrue(c2.unresolved.any { it.startsWith("query.is_item_name_any") })
    }

    @Test fun syntaxErrorsVsUnsupported() {
        for (bad in listOf("", "1 +", "(1 + 2", "math.sin()", "math.sin(1, 2)", "foo.bar", "1 2", "v.x =", "5 = 3", "1 ? 2 :")) {
            val e = Molang.check(bad)
            assertNotNull(e, "'$bad' deveria ser inválida")
            assertFalse(e!!.unsupported, "'$bad' é erro de sintaxe, não recurso não suportado")
        }
        for (u in listOf("array.a[0]", "loop(3, { v.x = 1; })", "math.unknown_fn(1)", "v.a[0]")) {
            val e = Molang.check(u)
            assertNotNull(e, u)
            assertTrue(e!!.unsupported, "'$u' deveria ser 'não suportado na prévia'")
        }
        assertNull(Molang.check("math.sin(q.anim_time * 360) * 20"))
    }
}

class AnimationTest {
    private val SRC = """
    {
      "format_version": "1.8.0",
      "x_root": 1,
      "animations": {
        "animation.golem.walk": {
          "loop": true,
          "animation_length": 1.0,
          "x_anim_extra": "keep",
          "bones": {
            "leg_l": {
              "rotation": {"0.0": [0, 0, 0], "0.5": {"post": [30, 0, 0], "lerp_mode": "catmullrom"}, "1.0": {"pre": [10, 0, 0], "post": [0, 0, 0]}},
              "position": [0, "math.sin(query.anim_time * 360)", 0],
              "x_bone_extra": true
            },
            "head": {"rotation": {"0.0": [0, 0, 0], "1.0": [0, 90, 0]}}
          }
        },
        "animation.golem.idle": {"loop": "hold_on_last_frame", "animation_length": 2, "bones": {}},
        "animation.golem.once": {"animation_length": 1, "bones": {"a": {"rotation": {"0.0": [0,0,0], "1.0": [90,0,0]}}}}
      }
    }
    """

    private fun load(name: String = "animation.golem.walk"): Pair<AnimationFile, Animation> {
        val f = AnimationFile.parse(SRC)
        return f to f.find(name)!!.animation!!
    }

    @Test fun parsesChannelsKeysAndModes() {
        val (_, a) = load()
        assertEquals(Loop.LOOP, a.loop)
        assertEquals(1.0, a.length)
        val rot = a.bones.getValue("leg_l").rotation!!
        assertEquals(listOf(0.0, 0.5, 1.0), rot.keys.map { it.time })
        assertTrue(rot.keys[1].smooth)
        assertTrue(rot.keys[2].split)
        assertEquals(AVec.of(10.0, 0.0, 0.0), rot.keys[2].pre)
        val pos = a.bones.getValue("leg_l").position!!
        assertTrue(pos.isConstant)
        assertEquals(Scalar.Expr("math.sin(query.anim_time * 360)"), pos.constant!!.y)
        assertEquals(Loop.HOLD, load("animation.golem.idle").second.loop)
    }

    @Test fun roundTripKeepsUnknownFieldsAndOtherAnimations() {
        val (f, a) = load()
        val edited = AnimationOps.setLength(a, 2.0)
        val text = f.replace(edited).toText()
        val root = Json.parseObject(text)
        assertEquals(JsonNum("1"), root["x_root"])
        val anims = root.obj("animations")!!
        assertEquals(listOf("animation.golem.walk", "animation.golem.idle", "animation.golem.once"), anims.fields.keys.toList())
        val walk = anims.obj("animation.golem.walk")!!
        assertEquals(JsonStr("keep"), walk["x_anim_extra"])
        assertEquals(JsonBool(true), walk.obj("bones")!!.obj("leg_l")!!["x_bone_extra"])
        val rot = walk.obj("bones")!!.obj("leg_l")!!.obj("rotation")!!
        assertEquals(listOf("0.0", "0.5", "1.0"), rot.fields.keys.toList())
        assertEquals(JsonStr("catmullrom"), (rot["0.5"] as JsonObj)["lerp_mode"])
        assertTrue((rot["1.0"] as JsonObj)["pre"] != null) // salto pre/post preservado
        assertTrue(rot["0.0"] is JsonArr) // valor simples volta como array
        assertEquals(JsonNum("2"), walk["animation_length"])
        // reler dá a mesma animação
        val again = AnimationFile.parse(text).find("animation.golem.walk")!!.animation!!
        assertEquals(edited.bones.keys, again.bones.keys)
        assertEquals(edited.bones["head"]!!.rotation!!.keys.map { it.time }, again.bones["head"]!!.rotation!!.keys.map { it.time })
    }

    @Test fun invalidAnimationIsReportedNotEdited() {
        val f = AnimationFile.parse("""{"format_version":"1.8.0","animations":{"animation.x":{"bones":{"b":{"rotation":{"abc":[0,0,0],"1.0":[1,2]}}}}}}""")
        val e = f.entries().single()
        assertNull(e.animation)
        assertTrue(e.diagnostics.any { it.code == "ANIM_TIME" })
        assertTrue(e.diagnostics.any { it.code == "ANIM_VALUE" })
    }

    @Test fun samplingLinearAndHoldAtEnds() {
        val (_, a) = load("animation.golem.once")
        val ctx = MolangContext()
        fun rotX(t: Double) = AnimSampler.sample(a, t, ctx).getValue("a").rotation.x
        assertEquals(0.0, rotX(0.0))
        assertTrue(abs(rotX(0.5) - 45.0) < 1e-9)
        assertEquals(90.0, rotX(1.0))
        assertEquals(90.0, rotX(5.0)) // sem loop: segura no fim
        assertEquals(0.0, rotX(-3.0))
    }

    @Test fun loopWrapsAndPrePostCreateAJump() {
        val (_, a) = load()
        val ctx = MolangContext()
        assertEquals(0.25, AnimSampler.wrapTime(a, 1.25))
        val head = { t: Double -> AnimSampler.sample(a, t, ctx).getValue("head").rotation.y }
        assertTrue(abs(head(1.25) - head(0.25)) < 1e-9)
        // salto: em 1.0 chega com pre=10 e sai com post=0
        val leg = a.bones.getValue("leg_l").rotation!!
        assertEquals(0.0, AnimSampler.sampleChannel(leg, 1.0, ctx)!!.x) // no último quadro vale o 'post'
        assertTrue(AnimSampler.sampleChannel(leg, 0.999, ctx)!!.x > 5.0) // chegando, vale perto do 'pre' (10)
    }

    @Test fun catmullRomPassesThroughKeysAndDiffersFromLinear() {
        val a = Animation("animation.c", length = 3.0, bones = mapOf("b" to BoneAnim(rotation = Channel(keys = listOf(
            Keyframe(0.0, AVec.of(0.0, 0.0, 0.0)),
            Keyframe(1.0, AVec.of(10.0, 0.0, 0.0), lerpMode = LerpMode.CATMULLROM),
            Keyframe(2.0, AVec.of(0.0, 0.0, 0.0)),
            Keyframe(3.0, AVec.of(-10.0, 0.0, 0.0)),
        )))))
        val ch = a.bones.getValue("b").rotation!!
        val ctx = MolangContext()
        assertEquals(10.0, AnimSampler.sampleChannel(ch, 1.0, ctx)!!.x)
        val smooth = AnimSampler.sampleChannel(ch, 1.5, ctx)!!.x
        assertTrue(abs(smooth - 5.0) > 0.1, "catmull-rom no meio do trecho difere do linear ($smooth)")
        val linear = AnimSampler.sampleChannel(ch.copy(keys = ch.keys.map { it.copy(lerpMode = null) }), 1.5, ctx)!!.x
        assertEquals(5.0, linear)
    }

    @Test fun molangKeyframesAreEvaluatedAndUnresolvedAreFlagged() {
        val (_, a) = load()
        val ctx = MolangContext()
        val p = AnimSampler.sample(a, 0.25, ctx).getValue("leg_l").position
        assertTrue(abs(p.y - Math.sin(Math.toRadians(0.25 * 360))) < 1e-9)
        val b = a.copy(bones = mapOf("x" to BoneAnim(position = Channel(constant = AVec(Scalar.Num(0.0), Scalar.Expr("query.ground_speed * 2"), Scalar.Expr("1 +"))))))
        val c2 = MolangContext()
        val q = AnimSampler.sample(b, 0.0, c2).getValue("x").position
        assertEquals(Vec3.ZERO, q)
        assertTrue("query.ground_speed" in c2.unresolved)
        assertTrue(c2.unresolved.any { it.startsWith("expressão inválida") })
        c2.queries["ground_speed"] = 3.0
        c2.unresolved.clear()
        assertEquals(6.0, AnimSampler.sample(b, 0.0, c2).getValue("x").position.y)
    }

    @Test fun playerPlayPauseRestartAndFinish() {
        val (_, once) = load("animation.golem.once")
        val p = AnimationPlayer(once)
        assertEquals(0.0, p.tick(0.5)) // parado não avança
        p.play()
        assertEquals(0.5, p.tick(0.5))
        p.pause()
        assertEquals(0.5, p.tick(10.0))
        p.play()
        p.tick(2.0)
        assertTrue(p.finished)
        assertFalse(p.playing)
        assertEquals(1.0, p.currentTime)
        p.play() // tocar de novo depois do fim recomeça
        assertEquals(0.0, p.currentTime)
        p.restart(); p.tick(0.25)
        p.speed = 2.0
        assertEquals(0.75, p.tick(0.25))
        assertFailsWith<IllegalArgumentException> { p.speed = 0.0 }
        val (_, walk) = load()
        val lp = AnimationPlayer(walk)
        lp.play()
        lp.tick(2.5)
        assertTrue(lp.playing)
        assertEquals(0.5, lp.currentTime)
        assertTrue(abs(lp.poses().getValue("head").rotation.y - 45.0) < 1e-9)
    }

    @Test fun keyframeEditing() {
        val ed = AnimationEditor(Animation("animation.t"))
        ed.setKeyframe("arm", ChannelKind.ROTATION, 0.0, Vec3.ZERO)
        ed.setKeyframe("arm", ChannelKind.ROTATION, 1.0, Vec3(90.0, 0.0, 0.0))
        ed.setKeyframe("arm", ChannelKind.ROTATION, 0.5, Vec3(10.0, 0.0, 0.0))
        assertEquals(listOf(0.0, 0.5, 1.0), ed.animation.bones.getValue("arm").rotation!!.keys.map { it.time })
        ed.setKeyframe("arm", ChannelKind.ROTATION, 0.50001, Vec3(20.0, 0.0, 0.0)) // mesmo instante: substitui
        assertEquals(3, ed.animation.bones.getValue("arm").rotation!!.keys.size)
        assertEquals(AVec.of(20.0, 0.0, 0.0), ed.animation.bones.getValue("arm").rotation!!.keys[1].post)
        ed.moveKeyframe("arm", ChannelKind.ROTATION, 1, 2.0)
        assertEquals(listOf(0.0, 1.0, 2.0), ed.animation.bones.getValue("arm").rotation!!.keys.map { it.time })
        assertFailsWith<EditException> { ed.moveKeyframe("arm", ChannelKind.ROTATION, 0, 1.0) }
        assertFailsWith<EditException> { ed.moveKeyframe("arm", ChannelKind.ROTATION, 0, -1.0) }
        ed.setKeyframeLerp("arm", ChannelKind.ROTATION, 1, LerpMode.CATMULLROM)
        assertTrue(ed.animation.bones.getValue("arm").rotation!!.keys[1].smooth)
        ed.fitLength()
        assertEquals(2.0, ed.animation.length)
        ed.deleteKeyframe("arm", ChannelKind.ROTATION, 2)
        ed.deleteKeyframe("arm", ChannelKind.ROTATION, 1)
        ed.deleteKeyframe("arm", ChannelKind.ROTATION, 0)
        assertTrue(ed.animation.bones.isEmpty()) // osso sem canais some
        assertTrue(ed.undo())
        assertEquals(1, ed.animation.bones.getValue("arm").rotation!!.keys.size)
        assertFailsWith<EditException> { ed.deleteKeyframe("arm", ChannelKind.ROTATION, 9) }
        assertFailsWith<EditException> { ed.setKeyframe("arm", ChannelKind.ROTATION, Double.NaN, Vec3.ZERO) }
    }

    @Test fun constantChannelBecomesKeyedWhenKeyAdded() {
        var a = Animation("animation.t")
        a = AnimationOps.setConstant(a, "b", ChannelKind.POSITION, AVec.of(0.0, 1.0, 0.0))
        a = AnimationOps.setKeyframe(a, "b", ChannelKind.POSITION, 1.0, AVec.of(0.0, 2.0, 0.0))
        val k = a.bones.getValue("b").position!!.keys
        assertEquals(listOf(0.0, 1.0), k.map { it.time })
        assertEquals(AVec.of(0.0, 1.0, 0.0), k[0].post)
        assertFailsWith<EditException> { AnimationOps.deleteKeyframe(AnimationOps.setConstant(Animation("animation.t"), "b", ChannelKind.SCALE, AVec.of(1.0, 1.0, 1.0)), "b", ChannelKind.SCALE, 0) }
    }

    @Test fun timeOperations() {
        val (_, a) = load("animation.golem.once")
        val s = AnimationOps.scaleTime(a, 2.0)
        assertEquals(2.0, s.length)
        assertEquals(listOf(0.0, 2.0), s.bones.getValue("a").rotation!!.keys.map { it.time })
        assertFailsWith<EditException> { AnimationOps.scaleTime(a, 0.0) }
        val r = AnimationOps.reverse(a)
        assertEquals(AVec.of(90.0, 0.0, 0.0), r.bones.getValue("a").rotation!!.keys[0].post)
        val sh = AnimationOps.shiftTime(a, 0.5)
        assertEquals(listOf(0.5, 1.5), sh.bones.getValue("a").rotation!!.keys.map { it.time })
        assertFailsWith<EditException> { AnimationOps.shiftTime(a, -0.5) }
        val t = AnimationOps.setLength(sh, 1.0, trim = true)
        assertEquals(1, t.bones.getValue("a").rotation!!.keys.size)
        assertFailsWith<EditException> { AnimationOps.setLength(a, 0.0) }
        assertFailsWith<EditException> { AnimationOps.setName(a, "walk") }
    }

    @Test fun mirroredCopyFlipsYzRotationAndXPosition() {
        var a = Animation("animation.t", length = 1.0)
        a = AnimationOps.setKeyframe(a, "leg_l", ChannelKind.ROTATION, 0.0, AVec(Scalar.Num(30.0), Scalar.Num(10.0), Scalar.Expr("math.sin(q.anim_time)")))
        a = AnimationOps.setConstant(a, "leg_l", ChannelKind.POSITION, AVec.of(2.0, 1.0, 0.0))
        val m = AnimationOps.copyBoneMirrored(a, "leg_l", "leg_r")
        val k = m.bones.getValue("leg_r").rotation!!.keys[0].post
        assertEquals(Scalar.Num(30.0), k.x)
        assertEquals(Scalar.Num(-10.0), k.y)
        assertEquals(Scalar.Expr("-(math.sin(q.anim_time))"), k.z)
        assertEquals(AVec.of(-2.0, 1.0, 0.0), m.bones.getValue("leg_r").position!!.constant)
        assertFailsWith<EditException> { AnimationOps.copyBoneMirrored(a, "leg_l", "leg_l") }
    }

    @Test fun boneRenameAndTimeline() {
        val (_, a) = load()
        val r = AnimationOps.renameBone(a, "head", "skull")
        assertTrue("skull" in r.bones && "head" !in r.bones)
        assertFailsWith<EditException> { AnimationOps.renameBone(a, "head", "LEG_L") }
        val rows = Timeline.rows(a)
        assertEquals(3, rows.size)
        assertEquals(listOf(0.0, 0.5, 1.0), rows.first { it.bone == "leg_l" && it.kind == ChannelKind.ROTATION }.times)
        assertTrue(rows.first { it.kind == ChannelKind.POSITION }.constant)
        assertEquals(0.5, Timeline.snap(0.52, 20))
        assertEquals(0.55, Timeline.snap(0.5333, 20))
        assertEquals(1, Timeline.nearestKey(listOf(0.0, 0.5, 1.0), 0.48, 0.05))
        assertNull(Timeline.nearestKey(listOf(0.0, 1.0), 0.5, 0.1))
    }

    @Test fun timeFormatting() {
        assertEquals("0.0", Num.fmtTime(0.0))
        assertEquals("0.25", Num.fmtTime(0.25))
        assertEquals("1.0", Num.fmtTime(1.0))
        assertEquals("12.5", Num.fmtTime(12.5))
        assertEquals("0.3333", Num.fmtTime(1.0 / 3))
        assertEquals("4", Num.fmt(4.0))
        assertEquals("0.1", Num.fmt(0.1 + 0.2 - 0.2))
    }

    @Test fun fileLevelOperations() {
        val (f, a) = load()
        assertFailsWith<GeometryFormatException> { f.add(a) }
        val dup = f.add(a.copy(name = "animation.golem.walk2"))
        assertEquals(4, dup.names().size)
        assertEquals(3, dup.remove("animation.golem.walk2").names().size)
        val renamed = f.replace(a.copy(name = "animation.golem.run"), "animation.golem.walk")
        assertEquals("animation.golem.run", renamed.names()[0]) // mantém a posição
        assertFailsWith<GeometryFormatException> { f.replace(a.copy(name = "animation.golem.idle"), "animation.golem.walk") }
        assertFailsWith<GeometryFormatException> { AnimationFile.parse("{\"a\":1}") }
    }
}
