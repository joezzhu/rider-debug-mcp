package com.github.joezzhu.debugmcp.api

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.xdebugger.*
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Proxy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DebuggerApiWrapper(private val project: Project) {

    private val debuggerManager: XDebuggerManager
        get() = XDebuggerManager.getInstance(project)

    fun getActiveSessions(): List<XDebugSession> = debuggerManager.debugSessions.toList()

    fun getSessionByName(name: String): XDebugSession? = getActiveSessions().find { it.sessionName == name }

    fun getBreakpoints(): List<XBreakpoint<*>> = debuggerManager.breakpointManager.allBreakpoints.toList()

    suspend fun listThreads(sessionName: String): List<XExecutionStack> {
        val session = getSessionByName(sessionName) ?: return emptyList()
        val suspendContext = session.suspendContext ?: return emptyList()
        return suspendContext.executionStacks.toList()
    }

    suspend fun getStackTrace(sessionName: String, threadName: String): List<XStackFrame> {
        val threads = listThreads(sessionName)
        val thread = findThread(threads, threadName) ?: return emptyList()

        // Try to get all frames via reflection on CIDR/Rider debugger backend first.
        // Rider's LLDB-based debugger may maintain a richer frame list internally
        // that includes frames filtered out by the standard computeStackFrames API.
        val reflectedFrames = tryGetAllFramesViaReflection(thread)
        if (reflectedFrames != null && reflectedFrames.isNotEmpty()) {
            return reflectedFrames
        }

        // Standard XDebugger API path:
        // Per API contract: firstFrameIndex=1 means the frame right below topFrame.
        // topFrame (index 0) is obtained separately via getTopFrame().
        val topFrame = thread.topFrame
        val remainingFrames = suspendCancellableCoroutine { continuation ->
            thread.computeStackFrames(1, object : XExecutionStack.XStackFrameContainer {
                private val frames = mutableListOf<XStackFrame>()

                override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                    frames.addAll(stackFrames)
                    if (last && continuation.isActive) {
                        continuation.resume(frames)
                    }
                }

                override fun errorOccurred(errorMessage: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(errorMessage))
                    }
                }
            })
        }

        return if (topFrame != null) {
            listOf(topFrame) + remainingFrames
        } else {
            remainingFrames
        }
    }

    /**
     * Attempt to get all stack frames via reflection on the underlying debugger backend.
     * Rider's CIDR debugger (LLDB-based) may expose a richer frame list through its
     * internal implementation that the standard computeStackFrames API filters.
     * Returns null if reflection fails (not a CIDR-based stack).
     */
    private fun tryGetAllFramesViaReflection(thread: XExecutionStack): List<XStackFrame>? {
        return try {
            val clazz = thread.javaClass
            // Try common internal method names that CIDR/Rider backends may expose
            val methodNames = listOf("getAllFrames", "getFrames", "getStackFrames", "getCachedFrames")
            for (methodName in methodNames) {
                val method = clazz.methods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0
                } ?: continue
                val result = method.invoke(thread)
                if (result is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val frames = result.filterIsInstance<XStackFrame>()
                    if (frames.isNotEmpty()) return frames
                }
            }
            // Try accessing internal frame list fields
            val fieldNames = listOf("myFrames", "frames", "allFrames", "stackFrames")
            for (fieldName in fieldNames) {
                val field = try {
                    clazz.getDeclaredField(fieldName)
                } catch (_: NoSuchFieldException) {
                    // Try superclasses
                    var superClass: Class<*>? = clazz.superclass
                    var found: java.lang.reflect.Field? = null
                    while (superClass != null && found == null) {
                        found = try { superClass.getDeclaredField(fieldName) } catch (_: NoSuchFieldException) { null }
                        superClass = superClass.superclass
                    }
                    found
                } ?: continue
                field.isAccessible = true
                val result = field.get(thread)
                if (result is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val frames = result.filterIsInstance<XStackFrame>()
                    if (frames.isNotEmpty()) return frames
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getVariables(sessionName: String, threadName: String, frameIndex: Int): List<FormattedValue> {
        val frame = getFrame(sessionName, threadName, frameIndex) ?: return emptyList()
        val children = collectChildren(frame)

        return buildList {
            for (i in 0 until children.size()) {
                add(formatValue(children.getName(i), children.getValue(i)))
            }
        }
    }

    suspend fun evaluateExpression(
        sessionName: String,
        threadName: String,
        frameIndex: Int,
        expression: String
    ): EvaluatedExpressionResult {
        val frame = getFrame(sessionName, threadName, frameIndex)
            ?: return EvaluatedExpressionResult.error(expression, "Frame index out of bounds")
        val evaluator = frame.evaluator ?: return EvaluatedExpressionResult.error(expression, "No evaluator available for this frame")

        // Phase 1: evaluate expression asynchronously to get XValue
        val xValue = suspendCancellableCoroutine { continuation ->
            evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: XValue) {
                    if (continuation.isActive) {
                        continuation.resume(Result.success(result))
                    }
                }

                override fun errorOccurred(errorMessage: String) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(RuntimeException(errorMessage)))
                    }
                }
            }, frame.sourcePosition)
        }

        return when {
            xValue.isFailure -> EvaluatedExpressionResult.error(expression, xValue.exceptionOrNull()?.message ?: "Evaluation failed")
            else -> {
                // Phase 2: format the XValue asynchronously (avoid blocking callback thread)
                // 10s timeout as safety net in case presentation callback never arrives
                try {
                    val value = xValue.getOrThrow()
                    val formatted = withTimeoutOrNull(10_000L) {
                        formatValue(null, value)
                    }
                    val needExpand = formatted == null
                            || formatted.value == "<unavailable>"
                            || formatted.value == "<presentation timeout>"
                            || formatted.hasChildren

                    // If value is unavailable or has children, expand members for richer info
                    val children = if (needExpand) {
                        try {
                            withTimeoutOrNull(10_000L) { collectXValueChildren(value) }
                        } catch (_: Exception) { null }
                    } else null

                    val childrenText = if (!children.isNullOrEmpty()) {
                        children.joinToString("\n") { "  ${it.toDisplayString()}" }
                    } else null

                    // Hint when value is unavailable (e.g. method call failed in optimized code)
                    val isUnavailable = formatted == null
                            || formatted.value == "<unavailable>"
                            || formatted.value == "<presentation timeout>"
                    val hint = if (isUnavailable) {
                        buildString {
                            append("HINT: Method calls may not be evaluable in optimized/native code. ")
                            append("Try accessing the member variable directly instead of calling a function. ")
                            append("For example, use 'obj->Array' instead of 'obj->Array.Num()'.")
                            if (frameIndex == 0) {
                                append("\nHINT: frameIndex is 0 (top of stack, possibly an inlined frame). ")
                                append("The evaluator may not have access to variables at this frame. ")
                                append("Try using frameIndex=1 or a deeper frame where variables are available.")
                            }
                        }
                    } else null

                    EvaluatedExpressionResult(
                        expression = expression,
                        value = formatted,
                        children = childrenText,
                        hint = hint,
                        sourceFilePath = frame.sourcePosition?.file?.path,
                        lineNumber = frame.sourcePosition?.line?.plus(1)
                    )
                } catch (e: Exception) {
                    EvaluatedExpressionResult.error(expression, "Formatting error: ${e.message}")
                }
            }
        }
    }

    suspend fun getSourceContext(
        sessionName: String,
        threadName: String,
        frameIndex: Int,
        contextLines: Int = 3
    ): SourceContext? {
        val frame = getFrame(sessionName, threadName, frameIndex) ?: return null
        val position = frame.sourcePosition ?: return null
        return getSourceContext(position, contextLines)
    }

    fun getDebugSessionStatus(sessionName: String): DebugSessionStatusSummary? {
        val session = getSessionByName(sessionName) ?: return null
        val currentPosition = session.currentPosition ?: session.currentStackFrame?.sourcePosition
        val suspendContext = session.suspendContext
        val threads = suspendContext?.executionStacks?.toList().orEmpty()

        return DebugSessionStatusSummary(
            sessionName = session.sessionName,
            isPaused = session.isPaused,
            isSuspended = session.isSuspended,
            threadCount = threads.size,
            currentFilePath = currentPosition?.file?.path,
            currentLineNumber = currentPosition?.line?.plus(1),
            currentFrameDescription = session.currentStackFrame?.let { formatFrameSummary(it) },
            evaluatorAvailable = session.currentStackFrame?.evaluator != null,
            enabledBreakpointCount = getBreakpoints().count { it.isEnabled }
        )
    }

    fun formatStackFrame(frame: XStackFrame, index: Int): String {
        val position = frame.sourcePosition
        val fileName = position?.file?.name ?: "Unknown"
        val filePath = position?.file?.path ?: "Unknown"
        val line = position?.line?.plus(1)?.toString() ?: "Unknown"
        // Extract the frame's display text (usually contains function signature)
        val funcName = try {
            val component = com.intellij.ui.SimpleColoredComponent()
            frame.customizePresentation(component)
            component.getCharSequence(false).toString().trim()
        } catch (_: Exception) { "" }
        val funcInfo = if (funcName.isNotBlank()) " $funcName |" else ""
        return "[$index]$funcInfo $fileName:$line ($filePath)"
    }

    fun formatBreakpoint(breakpoint: XBreakpoint<*>): String {
        val state = if (breakpoint.isEnabled) "Enabled" else "Disabled"
        return "Type: ${breakpoint.type.id}, State: $state"
    }

    /**
     * Fuzzy-match a thread by name. Supports:
     *   - Exact displayName match: "Thread-1-[Main Thread] (50124)"
     *   - Index only: "0"
     *   - list_threads output format: "[0] Thread-1-[Main Thread] (50124)"
     *   - Partial substring (case-insensitive): "Main Thread", "Thread-1"
     */
    private fun findThread(threads: List<XExecutionStack>, input: String): XExecutionStack? {
        val trimmed = input.trim()
        if (threads.isEmpty()) return null

        // 1. Exact match
        threads.find { it.displayName == trimmed }?.let { return it }

        // 2. Strip "[index] " prefix from list_threads output format, then exact match
        val stripped = trimmed.replace(Regex("""^\[\d+]\s*"""), "")
        if (stripped != trimmed) {
            threads.find { it.displayName == stripped }?.let { return it }
        }

        // 3. Pure numeric -> match by index
        trimmed.toIntOrNull()?.let { idx ->
            return threads.getOrNull(idx)
        }

        // 4. Case-insensitive substring match
        val lower = stripped.lowercase()
        threads.find { it.displayName.lowercase().contains(lower) }?.let { return it }

        // 5. Check if input matches content inside brackets, e.g. "Main Thread" matches "[Main Thread]"
        val bracketContent = Regex("""\[([^\]]+)]""").findAll(trimmed).map { it.groupValues[1] }.toList()
        for (keyword in bracketContent) {
            val kw = keyword.lowercase()
            threads.find { it.displayName.lowercase().contains(kw) }?.let { return it }
        }

        return null
    }

    private suspend fun getFrame(sessionName: String, threadName: String, frameIndex: Int): XStackFrame? {
        val frames = getStackTrace(sessionName, threadName)
        return frames.getOrNull(frameIndex)
    }

    private suspend fun collectChildren(frame: XStackFrame): XValueChildrenList {
        return suspendCancellableCoroutine { continuation ->
            val childrenList = XValueChildrenList()
            frame.computeChildren(object : XCompositeNode {
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    for (i in 0 until children.size()) {
                        childrenList.add(children.getName(i), children.getValue(i))
                    }
                    if (last && continuation.isActive) {
                        continuation.resume(childrenList)
                    }
                }

                override fun tooManyChildren(remaining: Int) {}
                override fun tooManyChildren(remaining: Int, addNextChildrenRunnable: Runnable) {}
                override fun setAlreadySorted(alreadySorted: Boolean) {}

                override fun setErrorMessage(errorMessage: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(errorMessage))
                    }
                }

                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(errorMessage))
                    }
                }

                override fun setMessage(
                    message: String,
                    icon: javax.swing.Icon?,
                    attributes: com.intellij.ui.SimpleTextAttributes,
                    link: XDebuggerTreeNodeHyperlink?
                ) {
                    if (message.isNotBlank() && continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(message))
                    }
                }
            })
        }
    }

    /**
     * Collect and format children of an XValue (e.g. object members, array elements).
     */
    private suspend fun collectXValueChildren(value: XValue): List<FormattedValue> {
        val childrenList = suspendCancellableCoroutine { continuation ->
            val list = XValueChildrenList()
            value.computeChildren(object : XCompositeNode {
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    for (i in 0 until children.size()) {
                        list.add(children.getName(i), children.getValue(i))
                    }
                    if (last && continuation.isActive) {
                        continuation.resume(list)
                    }
                }

                override fun tooManyChildren(remaining: Int) {
                    if (continuation.isActive) continuation.resume(list)
                }
                override fun tooManyChildren(remaining: Int, addNextChildrenRunnable: Runnable) {
                    if (continuation.isActive) continuation.resume(list)
                }
                override fun setAlreadySorted(alreadySorted: Boolean) {}

                override fun setErrorMessage(errorMessage: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(errorMessage))
                }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(errorMessage))
                }
                override fun setMessage(
                    message: String,
                    icon: javax.swing.Icon?,
                    attributes: com.intellij.ui.SimpleTextAttributes,
                    link: XDebuggerTreeNodeHyperlink?
                ) {
                    // Not an error, just info — ignore
                }
            })
        }

        return buildList {
            for (i in 0 until childrenList.size()) {
                val formatted = withTimeoutOrNull(5_000L) {
                    formatValue(childrenList.getName(i), childrenList.getValue(i))
                } ?: FormattedValue(name = childrenList.getName(i), value = "<timeout>")
                add(formatted)
            }
        }
    }

    private suspend fun formatValue(name: String?, value: XValue): FormattedValue {
        return suspendCancellableCoroutine { continuation ->
            var fullValueAvailable = false
            val proxy = Proxy.newProxyInstance(
                XValueNode::class.java.classLoader,
                arrayOf(XValueNode::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "setPresentation" -> {
                        val hasChildren = args?.lastOrNull() as? Boolean ?: false
                        val formatted = when (args?.size) {
                            // setPresentation(Icon, String type, String value, boolean hasChildren)
                            4 -> FormattedValue(
                                name = name,
                                type = args.getOrNull(1) as? String,
                                value = (args.getOrNull(2) as? String).orEmpty(),
                                hasChildren = hasChildren,
                                fullValueAvailable = fullValueAvailable
                            )

                            // setPresentation(Icon, XValuePresentation, boolean hasChildren)
                            3 -> {
                                val rendered = renderPresentation(args.getOrNull(1))
                                FormattedValue(
                                    name = name,
                                    type = rendered.type,
                                    value = rendered.value,
                                    hasChildren = hasChildren,
                                    fullValueAvailable = fullValueAvailable
                                )
                            }

                            // @Deprecated setPresentation(Icon, String type, String separator, String value, boolean hasChildren)
                            5 -> FormattedValue(
                                name = name,
                                type = args.getOrNull(1) as? String,
                                value = (args.getOrNull(3) as? String).orEmpty(),
                                hasChildren = hasChildren,
                                fullValueAvailable = fullValueAvailable
                            )

                            else -> FormattedValue(name = name, value = "<unavailable>")
                        }

                        if (continuation.isActive) {
                            continuation.resume(formatted)
                        }
                        null
                    }

                    "setFullValueEvaluator" -> {
                        fullValueAvailable = true
                        null
                    }

                    "isObsolete" -> false
                    else -> defaultValue(method.returnType)
                }
            } as XValueNode

            try {
                value.computePresentation(proxy, XValuePlace.TREE)
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun runBlockingValueFormatting(value: XValue): FormattedValue {
        var result: Result<FormattedValue>? = null
        val lock = Object()

        val proxy = Proxy.newProxyInstance(
            XValueNode::class.java.classLoader,
            arrayOf(XValueNode::class.java)
        ) { _, method, args ->
            when (method.name) {
                "setPresentation" -> {
                    synchronized(lock) {
                        if (result == null) {
                            val hasChildren = args?.lastOrNull() as? Boolean ?: false
                            result = when (args?.size) {
                                4 -> Result.success(
                                    FormattedValue(
                                        type = args.getOrNull(1) as? String,
                                        value = (args.getOrNull(2) as? String).orEmpty(),
                                        hasChildren = hasChildren
                                    )
                                )

                                3 -> {
                                    val rendered = renderPresentation(args.getOrNull(1))
                                    Result.success(
                                        FormattedValue(
                                            type = rendered.type,
                                            value = rendered.value,
                                            hasChildren = hasChildren
                                        )
                                    )
                                }

                                5 -> Result.success(
                                    FormattedValue(
                                        type = args.getOrNull(1) as? String,
                                        value = (args.getOrNull(3) as? String).orEmpty(),
                                        hasChildren = hasChildren
                                    )
                                )

                                else -> Result.success(FormattedValue(value = "<unavailable>"))
                            }
                            lock.notifyAll()
                        }
                    }
                    null
                }

                "isObsolete" -> false
                else -> defaultValue(method.returnType)
            }
        } as XValueNode

        synchronized(lock) {
            value.computePresentation(proxy, XValuePlace.TREE)
            while (result == null) {
                lock.wait(1_000)
                if (result == null) {
                    result = Result.success(FormattedValue(value = "<presentation timeout>"))
                }
            }
        }

        return result!!.getOrThrow()
    }

    private fun renderPresentation(presentation: Any?): RenderedPresentation {
        if (presentation == null) {
            return RenderedPresentation(type = null, value = "<unavailable>")
        }

        val type = invokeStringMethod(presentation, "getType")
        val renderedValue = StringBuilder()

        runCatching {
            val rendererClass = Class.forName("com.intellij.xdebugger.frame.presentation.XValuePresentation\$XValueTextRenderer")
            val renderer = Proxy.newProxyInstance(rendererClass.classLoader, arrayOf(rendererClass)) { _, method, args ->
                appendRenderedText(renderedValue, method.name, args)
                defaultValue(method.returnType)
            }

            presentation.javaClass.methods
                .firstOrNull { it.name == "renderValue" && it.parameterCount == 1 }
                ?.invoke(presentation, renderer)
        }

        return RenderedPresentation(
            type = type,
            value = renderedValue.toString().ifBlank { "<unavailable>" }
        )
    }

    private fun appendRenderedText(target: StringBuilder, methodName: String, args: Array<out Any?>?) {
        val items = args?.toList().orEmpty()
        val textArgs = items.mapNotNull {
            when (it) {
                is String -> it
                is Char -> it.toString()
                else -> null
            }
        }

        when (methodName) {
            "renderStringValue" -> {
                val value = textArgs.firstOrNull().orEmpty()
                val quote = items.firstOrNull { it is Char }?.toString() ?: "\""
                target.append(quote).append(value).append(quote)
            }

            else -> textArgs.forEach(target::append)
        }
    }

    private fun invokeStringMethod(target: Any, name: String): String? {
        return runCatching {
            target.javaClass.methods
                .firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.invoke(target) as? String
        }.getOrNull()
    }

    private fun defaultValue(returnType: Class<*>): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private fun getSourceContext(position: XSourcePosition, contextLines: Int): SourceContext? {
        val file = position.file
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val currentLineIndex = position.line.coerceIn(0, document.lineCount - 1)
        val startLineIndex = (currentLineIndex - contextLines).coerceAtLeast(0)
        val endLineIndex = (currentLineIndex + contextLines).coerceAtMost(document.lineCount - 1)

        val lines = buildList {
            for (lineIndex in startLineIndex..endLineIndex) {
                val startOffset = document.getLineStartOffset(lineIndex)
                val endOffset = document.getLineEndOffset(lineIndex)
                add(
                    SourceContextLine(
                        number = lineIndex + 1,
                        text = document.getText(TextRange(startOffset, endOffset)),
                        isCurrentLine = lineIndex == currentLineIndex
                    )
                )
            }
        }

        return SourceContext(
            filePath = file.path,
            currentLineNumber = currentLineIndex + 1,
            startLineNumber = startLineIndex + 1,
            endLineNumber = endLineIndex + 1,
            lines = lines
        )
    }

    private fun formatFrameSummary(frame: XStackFrame): String {
        val position = frame.sourcePosition ?: return "No source position"
        return "${position.file.name}:${position.line + 1} (${position.file.path})"
    }
}

data class FormattedValue(
    val name: String? = null,
    val type: String? = null,
    val value: String,
    val hasChildren: Boolean = false,
    val fullValueAvailable: Boolean = false
) {
    /**
     * Compact one-line format: `name : type = value [+]`
     * The [+] suffix indicates expandable (has children).
     */
    fun toDisplayString(): String = buildString {
        append(name?.takeIf { it.isNotBlank() } ?: "<result>")
        type?.takeIf { it.isNotBlank() }?.let {
            append(" : ")
            append(it)
        }
        append(" = ")
        append(value.ifBlank { "<empty>" })
        if (hasChildren) append(" [+]")
        if (fullValueAvailable) append(" [full]")
    }
}

data class EvaluatedExpressionResult(
    val expression: String,
    val value: FormattedValue? = null,
    val children: String? = null,
    val hint: String? = null,
    val sourceFilePath: String? = null,
    val lineNumber: Int? = null,
    val errorMessage: String? = null
) {
    fun toDisplayString(): String = buildString {
        append("expr: ")
        append(expression)
        if (errorMessage != null) {
            append("\nerror: ")
            append(errorMessage)
        } else {
            sourceFilePath?.let {
                append("\nsource: ")
                append(it)
                lineNumber?.let { line -> append(":").append(line) }
            }
            value?.let {
                append("\nresult: ")
                append(it.toDisplayString())
            }
            children?.let {
                append("\nmembers:\n")
                append(it)
            }
            hint?.let {
                append("\n")
                append(it)
            }
        }
    }

    companion object {
        fun error(expression: String, errorMessage: String): EvaluatedExpressionResult =
            EvaluatedExpressionResult(expression = expression, errorMessage = errorMessage)
    }
}

data class SourceContextLine(
    val number: Int,
    val text: String,
    val isCurrentLine: Boolean
)

data class SourceContext(
    val filePath: String,
    val currentLineNumber: Int,
    val startLineNumber: Int,
    val endLineNumber: Int,
    val lines: List<SourceContextLine>
) {
    fun toDisplayString(): String = buildString {
        append("file: ")
        append(filePath)
        append("\nrange: ")
        append(startLineNumber)
        append("-")
        append(endLineNumber)
        append("\n")
        append(lines.joinToString("\n") { line ->
            val marker = if (line.isCurrentLine) ">" else " "
            "$marker ${line.number.toString().padStart(4, ' ')} | ${line.text}"
        })
    }
}

data class DebugSessionStatusSummary(
    val sessionName: String,
    val isPaused: Boolean,
    val isSuspended: Boolean,
    val threadCount: Int,
    val currentFilePath: String?,
    val currentLineNumber: Int?,
    val currentFrameDescription: String?,
    val evaluatorAvailable: Boolean,
    val enabledBreakpointCount: Int
) {
    fun toDisplayString(): String = buildString {
        append("session: ")
        append(sessionName)
        append("\npaused: ")
        append(isPaused)
        append("\nsuspended: ")
        append(isSuspended)
        append("\nthreads: ")
        append(threadCount)
        append("\nenabledBreakpoints: ")
        append(enabledBreakpointCount)
        append("\nevaluatorAvailable: ")
        append(evaluatorAvailable)
        currentFilePath?.let {
            append("\ncurrentSource: ")
            append(it)
            currentLineNumber?.let { line ->
                append(":")
                append(line)
            }
        }
        currentFrameDescription?.let {
            append("\ncurrentFrame: ")
            append(it)
        }
    }
}

private data class RenderedPresentation(
    val type: String?,
    val value: String
)
