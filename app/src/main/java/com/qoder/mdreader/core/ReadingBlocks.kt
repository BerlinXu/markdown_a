package com.qoder.mdreader.core

/** Bound the cost of one recycled row, including nested tables and lists. */
object ReadingBlocks {
    fun prepare(blocks: List<Block>): List<Block> = buildList {
        fun append(block: Block) {
            when (block) {
                is Block.ListBlock -> block.items.forEachIndexed { index, item ->
                    val children = prepare(item.blocks)
                    children.chunked(12).forEachIndexed { part, batch ->
                        add(Block.ListBlock(block.ordered, block.start + index,
                            listOf(item.copy(blocks = batch, task = if (part == 0) item.task else TaskState.NONE, continuation = part > 0)), item.markerLine))
                    }
                    if (children.isEmpty()) add(block.copy(start = block.start + index, items = listOf(item), line = item.markerLine))
                }
                is Block.Quote -> prepare(block.kids).forEach { add(Block.Quote(listOf(it), it.line)) }
                is Block.Table -> {
                    if (block.header.size > 20 || (block.header + block.rows.flatten()).any { flatten(it).length > 4000 }) {
                        append(Block.CodeBlock("text", blocksToPlainText(listOf(block)), block.line))
                    } else if (block.rows.isEmpty()) add(block) else {
                        val maxRowChars = block.rows.maxOfOrNull { row -> row.sumOf { flatten(it).length } }?.coerceAtLeast(1) ?: 1
                        val batch = minOf(30, (16000 / maxRowChars).coerceAtLeast(1))
                        block.rows.chunked(batch).forEachIndexed { index, rows ->
                            add(block.copy(rows = rows, line = if (index == 0) block.line else block.line + index * batch + 2))
                        }
                    }
                }
                is Block.CodeBlock -> {
                    val lines = block.text.lineSequence().mapIndexed { index, text ->
                        text.chunked(4000).ifEmpty { listOf("") }.map { it to (block.line + index) }
                    }.flatten().toList()
                    val chunk = ArrayList<Pair<String, Int>>()
                    var chars = 0
                    fun flush() {
                        if (chunk.isNotEmpty()) add(block.copy(text = chunk.joinToString("\n") { it.first }, line = chunk.first().second))
                        chunk.clear(); chars = 0
                    }
                    lines.forEach { part ->
                        if (chunk.size >= 80 || chars + part.first.length > 12000) flush()
                        chunk += part; chars += part.first.length + 1
                    }
                    flush()
                }
                is Block.Paragraph -> {
                    val text = flatten(block.inline)
                    if (text.length <= 16000) add(block)
                    else text.chunked(16000).forEach { add(Block.Paragraph(listOf(Inline.Text(it)), block.line)) }
                }
                is Block.Heading -> if (flatten(block.inline).length <= 16000) add(block)
                    else add(block.copy(inline = listOf(Inline.Text(flatten(block.inline).take(16000) + "…"))))
                else -> add(block)
            }
        }
        blocks.forEach { append(it) }
    }
}
