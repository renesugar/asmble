package asmble.compile.jvm

import asmble.ast.Node

open class InsnReworker {


    fun rework(ctx: ClsContext, insns: List<Node.Instr>, retType: Node.Type.Value?): List<Insn> {
        return injectNeededStackVars(ctx, insns).let { insns -> wrapWithImplicitBlock(ctx, insns, retType) }
    }

    fun wrapWithImplicitBlock(ctx: ClsContext, insns: List<Insn>, retType: Node.Type.Value?) =
        (listOf(Insn.Node(Node.Instr.Block(retType))) + insns) + Insn.Node(Node.Instr.End)

    fun injectNeededStackVars(ctx: ClsContext, insns: List<Node.Instr>): List<Insn> {
        // How we do this:
        // We run over each insn, and keep a running list of stack
        // manips. If there is an insn that needs something so far back,
        // we calc where it needs to be added and keep a running list of
        // insn inserts. Then at the end we settle up.
        //
        // Note, we don't do any injections for things like "this" if
        // they aren't needed up the stack (e.g. a simple getfield can
        // just aload 0 itself)

        // Each pair is first the amount of stack that is changed (0 is
        // ignored, push is positive, pull is negative) then the index
        // of the insn that caused it. As a special case, if the stack
        // is dynamic (i.e. call_indirect
        var stackManips = emptyList<Pair<Int, Int>>()

        // Keyed by the index to inject. With how the algorithm works, we
        // guarantee the value will be in the right order if there are
        // multiple for the same index
        var insnsToInject = emptyMap<Int, List<Insn>>()
        fun injectBeforeLastStackCount(insn: Insn, count: Int) {
            ctx.trace { "Injecting $insn back $count stack values" }
            fun inject(index: Int) {
                insnsToInject += index to (insnsToInject[index]?.let { listOf(insn) + it } ?: listOf(insn))
            }
            if (count == 0) return inject(stackManips.size)
            var countSoFar = 0
            for ((amountChanged, insnIndex) in stackManips.asReversed()) {
                countSoFar += amountChanged
                if (countSoFar == count) return inject(insnIndex)
            }
            throw CompileErr.StackInjectionMismatch(count, insn)
        }

        // Go over each insn, determining where to inject
        insns.forEachIndexed { index, insn ->
            // Handle special injection cases
            when (insn) {
                // Calls require "this" or fn ref before the params
                is Node.Instr.Call -> {
                    val inject =
                        if (insn.index < ctx.importFuncs.size) Insn.ImportFuncRefNeededOnStack(insn.index)
                        else Insn.ThisNeededOnStack
                    injectBeforeLastStackCount(inject, ctx.funcTypeAtIndex(insn.index).params.size)
                }
                is Node.Instr.CallIndirect -> TODO("Not sure what I need yet")
                // Global set requires "this" before the single param
                is Node.Instr.SetGlobal -> {
                    val inject =
                        if (insn.index < ctx.importGlobals.size) Insn.ImportGlobalSetRefNeededOnStack(insn.index)
                        else Insn.ThisNeededOnStack
                    injectBeforeLastStackCount(inject, 1)
                }
                // Loads require "mem" before the single param
                is Node.Instr.I32Load, is Node.Instr.I64Load, is Node.Instr.F32Load, is Node.Instr.F64Load,
                is Node.Instr.I32Load8S, is Node.Instr.I32Load8U, is Node.Instr.I32Load16U, is Node.Instr.I32Load16S,
                is Node.Instr.I64Load8S, is Node.Instr.I64Load8U, is Node.Instr.I64Load16U, is Node.Instr.I64Load16S,
                is Node.Instr.I64Load32S, is Node.Instr.I64Load32U ->
                    injectBeforeLastStackCount(Insn.MemNeededOnStack, 1)
                // Storage requires "mem" before the single param
                is Node.Instr.I32Store, is Node.Instr.I64Store, is Node.Instr.F32Store, is Node.Instr.F64Store,
                is Node.Instr.I32Store8, is Node.Instr.I32Store16, is Node.Instr.I64Store8, is Node.Instr.I64Store16,
                is Node.Instr.I64Store32 ->
                    injectBeforeLastStackCount(Insn.MemNeededOnStack, 1)
                // Grow memory requires "mem" before the single param
                is Node.Instr.GrowMemory ->
                    injectBeforeLastStackCount(Insn.MemNeededOnStack, 1)
                else -> { }
            }

            // Add the current diff
            ctx.trace { "Stack diff is ${insnStackDiff(ctx, insn)} for $insn" }
            stackManips += insnStackDiff(ctx, insn) to index
        }

        // Build resulting list
        return insns.foldIndexed(emptyList<Insn>()) { index, ret, insn ->
            val injections = insnsToInject[index] ?: emptyList()
            ret + injections + Insn.Node(insn)
        }
    }

    fun insnStackDiff(ctx: ClsContext, insn: Node.Instr) = when (insn) {
        is Node.Instr.Unreachable, is Node.Instr.Nop, is Node.Instr.Block,
        is Node.Instr.Loop, is Node.Instr.If, is Node.Instr.Else,
        is Node.Instr.End, is Node.Instr.Br, is Node.Instr.BrIf,
        is Node.Instr.BrTable, is Node.Instr.Return -> NOP
        is Node.Instr.Call -> ctx.funcTypeAtIndex(insn.index).let {
            // All calls pop "this" + params, and any return is a push
            POP_THIS + (POP_PARAM + it.params.size) + (if (it.ret == null) NOP else PUSH_RESULT)
        }
        is Node.Instr.CallIndirect -> ctx.mod.types[insn.index].let {
            POP_THIS + (POP_PARAM + it.params.size) + (if (it.ret == null) NOP else PUSH_RESULT)
        }
        is Node.Instr.Drop -> POP_PARAM
        is Node.Instr.Select -> (POP_PARAM * 3) + PUSH_RESULT
        is Node.Instr.GetLocal -> PUSH_RESULT
        is Node.Instr.SetLocal -> POP_PARAM
        is Node.Instr.TeeLocal -> POP_PARAM + PUSH_RESULT
        is Node.Instr.GetGlobal -> POP_THIS + PUSH_RESULT
        is Node.Instr.SetGlobal -> POP_THIS + POP_PARAM
        is Node.Instr.I32Load, is Node.Instr.I64Load, is Node.Instr.F32Load, is Node.Instr.F64Load,
        is Node.Instr.I32Load8S, is Node.Instr.I32Load8U, is Node.Instr.I32Load16U, is Node.Instr.I32Load16S,
        is Node.Instr.I64Load8S, is Node.Instr.I64Load8U, is Node.Instr.I64Load16U, is Node.Instr.I64Load16S,
        is Node.Instr.I64Load32S, is Node.Instr.I64Load32U -> POP_PARAM + PUSH_RESULT
        is Node.Instr.I32Store, is Node.Instr.I64Store, is Node.Instr.F32Store, is Node.Instr.F64Store,
        is Node.Instr.I32Store8, is Node.Instr.I32Store16, is Node.Instr.I64Store8, is Node.Instr.I64Store16,
        is Node.Instr.I64Store32 -> POP_PARAM
        is Node.Instr.CurrentMemory -> PUSH_RESULT
        is Node.Instr.GrowMemory -> POP_PARAM
        is Node.Instr.I32Const, is Node.Instr.I64Const,
        is Node.Instr.F32Const, is Node.Instr.F64Const -> PUSH_RESULT
        is Node.Instr.I32Add, is Node.Instr.I32Sub, is Node.Instr.I32Mul, is Node.Instr.I32DivS,
        is Node.Instr.I32DivU, is Node.Instr.I32RemS, is Node.Instr.I32RemU, is Node.Instr.I32And,
        is Node.Instr.I32Or, is Node.Instr.I32Xor, is Node.Instr.I32Shl, is Node.Instr.I32ShrS,
        is Node.Instr.I32ShrU, is Node.Instr.I32Rotl, is Node.Instr.I32Rotr, is Node.Instr.I32Eq,
        is Node.Instr.I32Ne, is Node.Instr.I32LtS, is Node.Instr.I32LeS, is Node.Instr.I32LtU,
        is Node.Instr.I32LeU, is Node.Instr.I32GtS, is Node.Instr.I32GeS, is Node.Instr.I32GtU,
        is Node.Instr.I32GeU -> POP_PARAM + POP_PARAM + PUSH_RESULT
        is Node.Instr.I32Clz, is Node.Instr.I32Ctz, is Node.Instr.I32Popcnt,
        is Node.Instr.I32Eqz -> POP_PARAM + PUSH_RESULT
        is Node.Instr.I64Add, is Node.Instr.I64Sub, is Node.Instr.I64Mul, is Node.Instr.I64DivS,
        is Node.Instr.I64DivU, is Node.Instr.I64RemS, is Node.Instr.I64RemU, is Node.Instr.I64And,
        is Node.Instr.I64Or, is Node.Instr.I64Xor, is Node.Instr.I64Shl, is Node.Instr.I64ShrS,
        is Node.Instr.I64ShrU, is Node.Instr.I64Rotl, is Node.Instr.I64Rotr, is Node.Instr.I64Eq,
        is Node.Instr.I64Ne, is Node.Instr.I64LtS, is Node.Instr.I64LeS, is Node.Instr.I64LtU,
        is Node.Instr.I64LeU, is Node.Instr.I64GtS, is Node.Instr.I64GeS, is Node.Instr.I64GtU,
        is Node.Instr.I64GeU -> POP_PARAM + POP_PARAM + PUSH_RESULT
        is Node.Instr.I64Clz, is Node.Instr.I64Ctz, is Node.Instr.I64Popcnt,
        is Node.Instr.I64Eqz -> POP_PARAM + PUSH_RESULT
        is Node.Instr.F32Add, is Node.Instr.F32Sub, is Node.Instr.F32Mul, is Node.Instr.F32Div,
        is Node.Instr.F32Eq, is Node.Instr.F32Ne, is Node.Instr.F32Lt, is Node.Instr.F32Le,
        is Node.Instr.F32Gt, is Node.Instr.F32Ge, is Node.Instr.F32Sqrt, is Node.Instr.F32Min,
        is Node.Instr.F32Max -> POP_PARAM + POP_PARAM + PUSH_RESULT
        is Node.Instr.F32Abs, is Node.Instr.F32Neg, is Node.Instr.F32CopySign, is Node.Instr.F32Ceil,
        is Node.Instr.F32Floor, is Node.Instr.F32Trunc, is Node.Instr.F32Nearest -> POP_PARAM + PUSH_RESULT
        is Node.Instr.F64Add, is Node.Instr.F64Sub, is Node.Instr.F64Mul, is Node.Instr.F64Div,
        is Node.Instr.F64Eq, is Node.Instr.F64Ne, is Node.Instr.F64Lt, is Node.Instr.F64Le,
        is Node.Instr.F64Gt, is Node.Instr.F64Ge, is Node.Instr.F64Sqrt, is Node.Instr.F64Min,
        is Node.Instr.F64Max -> POP_PARAM + POP_PARAM + PUSH_RESULT
        is Node.Instr.F64Abs, is Node.Instr.F64Neg, is Node.Instr.F64CopySign, is Node.Instr.F64Ceil,
        is Node.Instr.F64Floor, is Node.Instr.F64Trunc, is Node.Instr.F64Nearest -> POP_PARAM + PUSH_RESULT
        is Node.Instr.I32WrapI64, is Node.Instr.I32TruncSF32, is Node.Instr.I32TruncUF32,
        is Node.Instr.I32TruncSF64, is Node.Instr.I32TruncUF64, is Node.Instr.I64ExtendSI32,
        is Node.Instr.I64ExtendUI32, is Node.Instr.I64TruncSF32, is Node.Instr.I64TruncUF32,
        is Node.Instr.I64TruncSF64, is Node.Instr.I64TruncUF64, is Node.Instr.F32ConvertSI32,
        is Node.Instr.F32ConvertUI32, is Node.Instr.F32ConvertSI64, is Node.Instr.F32ConvertUI64,
        is Node.Instr.F32DemoteF64, is Node.Instr.F64ConvertSI32, is Node.Instr.F64ConvertUI32,
        is Node.Instr.F64ConvertSI64, is Node.Instr.F64ConvertUI64, is Node.Instr.F64PromoteF32,
        is Node.Instr.I32ReinterpretF32, is Node.Instr.I64ReinterpretF64, is Node.Instr.F32ReinterpretI32,
        is Node.Instr.F64ReinterpretI64 -> POP_PARAM + PUSH_RESULT
    }

    fun nonAdjacentMemAccesses(insns: List<Insn>) = insns.fold(0 to false) { (count, lastCouldHaveMem), insn ->
        val inc =
            if (lastCouldHaveMem) 0
            else if (insn == Insn.MemNeededOnStack) 1
            else if (insn is Insn.Node && insn.insn is Node.Instr.CurrentMemory) 1
            else 0
        val couldSetMemNext = if (insn !is Insn.Node) false else when (insn.insn) {
            is Node.Instr.I32Store, is Node.Instr.I64Store, is Node.Instr.F32Store, is Node.Instr.F64Store,
            is Node.Instr.I32Store8, is Node.Instr.I32Store16, is Node.Instr.I64Store8, is Node.Instr.I64Store16,
            is Node.Instr.I64Store32, is Node.Instr.GrowMemory -> true
            else -> false
        }
        (count + inc) to couldSetMemNext
    }.let { (count, _) -> count }

    companion object : InsnReworker() {
        const val POP_THIS = -1
        const val POP_PARAM = -1
        const val PUSH_RESULT = 1
        const val NOP = 0
    }
}