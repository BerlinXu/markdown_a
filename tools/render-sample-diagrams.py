#!/usr/bin/env python3
"""Regenerate the original, MIT-licensed example diagrams. No build-time dependency.

python tools/render-sample-diagrams.py                    # editable XML only
python tools/render-sample-diagrams.py --render --preview # clean review PNGs
python tools/render-sample-diagrams.py --render           # bundled editable PNGs
Pass --drawio /path/to/drawio if it is not on PATH.
"""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import struct
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "docs/illustrations"
ASSETS = ROOT / "app/src/main/assets/sample-images"


class Diagram:
    def __init__(self, name, title, subtitle, height=1160):
        self.name = name
        self.height = height
        self.doc = ET.Element("mxfile", host="drawio", version="31.4.4")
        page = ET.SubElement(self.doc, "diagram", id=name, name=title)
        graph = ET.SubElement(page, "mxGraphModel", page="1", pageWidth="760", pageHeight=str(height), background="#FFFDF7")
        self.root = ET.SubElement(graph, "root")
        ET.SubElement(self.root, "mxCell", id="0")
        ET.SubElement(self.root, "mxCell", id="1", parent="0")
        self.seq = 1
        self.text(title, 30, 30, 700, 60, 32, True)
        self.text(subtitle, 40, 90, 680, 50, 21)

    def vertex(self, text, x, y, w, h, style):
        self.seq += 1
        key = str(self.seq)
        cell = ET.SubElement(self.root, "mxCell", id=key, value=text, style=style, vertex="1", parent="1")
        ET.SubElement(cell, "mxGeometry", x=str(x), y=str(y), width=str(w), height=str(h), **{"as": "geometry"})
        return key

    def text(self, text, x, y, w, h, size=24, bold=False):
        return self.vertex(text, x, y, w, h,
            f"text;html=1;whiteSpace=wrap;align=center;verticalAlign=middle;fontFamily=Microsoft YaHei;fontSize={size};fontColor=#284B40;fontStyle={int(bold)};spacing=6;")

    def box(self, text, x, y, w=600, h=100, blue=False):
        fill, stroke = ("#dae8fc", "#6c8ebf") if blue else ("#d5e8d4", "#82b366")
        return self.vertex(text, x, y, w, h,
            f"rounded=1;arcSize=14;whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};strokeWidth=2;fontFamily=Microsoft YaHei;fontSize=26;fontColor=#243D33;spacing=14;")

    def arrow(self, source, target, vertical=True, points=(), both=False):
        self.seq += 1
        ports = "exitX=0.5;exitY=1;entryX=0.5;entryY=0;" if vertical else "exitX=1;exitY=0.5;entryX=0;entryY=0.5;"
        style = "edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#658472;endArrow=block;endFill=1;" + ports
        if both:
            style += "startArrow=block;startFill=1;"
        cell = ET.SubElement(self.root, "mxCell", id=str(self.seq), value="", style=style,
                             edge="1", parent="1", source=source, target=target)
        geom = ET.SubElement(cell, "mxGeometry", relative="1", **{"as": "geometry"})
        if points:
            array = ET.SubElement(geom, "Array", **{"as": "points"})
            for x, y in points:
                ET.SubElement(array, "mxPoint", x=str(x), y=str(y))

    def footer(self, text):
        self.text(text, 40, self.height - 100, 680, 60, 21)

    def save(self):
        SOURCES.mkdir(parents=True, exist_ok=True)
        out = SOURCES / (self.name + ".drawio")
        ET.indent(self.doc, space="  ")
        ET.ElementTree(self.doc).write(out, encoding="utf-8", xml_declaration=True)
        return out


def chain(name, title, subtitle, labels, note):
    d = Diagram(name, title, subtitle, 200 + len(labels) * 160 + 100)
    previous = None
    for i, label in enumerate(labels):
        node = d.box(label, 80, 160 + i * 160, h=110)
        if previous:
            d.arrow(previous, node)
        previous = node
    d.footer(note)
    return d


def diagrams():
    yield chain("01-timeline", "计算如何走到今天", "六个理解阶段 · 并非严格的年代分界", [
        "方法与机械\n算法、数表、齿轮、穿孔卡", "电子与存储程序\n把执行步骤也编码保存",
        "芯片与个人电脑\n集成制造，让计算走出机房", "网络与云\n连接设备、资料和计算资源",
        "机器学习与深度学习\n用数据和目标调整模型", "生成式 AI 与工具系统\n多模态、检索、执行与核验"], "许多路线长期并行；新技术依靠旧基础设施。")
    yield chain("02-abstraction", "从物理状态到应用", "每一层通过接口，支持更高一层的抽象", [
        "物理器件\n晶体管、电荷、电压与制造工艺", "数字电路\n逻辑门、寄存器、运算与控制",
        "计算硬件\n处理器、内存、存储与输入输出", "基础软件\n操作系统、编译器、数据库",
        "程序与模型\n明确规则、可训练参数、工具接口", "用户任务\n阅读、创作、检索与协作"], "抽象隐藏细节，不会消除成本与约束。")
    d = Diagram("03-computer", "存储程序计算机", "逻辑结构示意 · 不是主板接线图", 1000)
    a = d.box("输入\n键盘、文件、传感器", 40, 180, 280, 140)
    b = d.box("处理器 CPU\n控制与运算", 440, 180, 280, 140)
    c = d.box("输出\n屏幕、打印、执行器", 40, 480, 280, 140)
    e = d.box("工作内存\n保存指令和数据", 440, 480, 280, 140, True)
    f = d.box("持久存储\n程序和文件", 220, 760, 320, 120, True)
    d.arrow(a, b, False); d.arrow(b, e, both=True)
    d.arrow(b, c, points=[(580, 400), (180, 400)])
    d.arrow(e, f, points=[(580, 690), (380, 690)], both=True)
    d.footer("实际系统还包括缓存、总线和专用加速器。")
    yield d
    yield chain("04-network", "一次联网请求的旅程", "逻辑层次 · 实际部署可以合并或拆分", [
        "终端\n手机、电脑与本地应用", "网络与协议\n寻址、转发、连接与名称解析",
        "应用服务\n验证请求，组织业务与模型计算", "数据与计算资源\n数据库、文件、索引和加速器"], "延迟不仅来自带宽，也来自排队、距离与处理。")
    d = Diagram("05-learning", "从规则到学习", "训练是求参数；部署是处理新输入", 1080)
    d.text("明确编程", 40, 150, 300, 50, 27, True)
    d.text("机器学习", 420, 150, 300, 50, 27, True)
    left = [d.box(t, 40, 240+i*200, 300, 130) for i,t in enumerate(["开发者编写规则\n加上待处理数据", "程序执行\n明确的控制流程", "输出结果\n用测试检查程序"])]
    right = [d.box(t, 420, 240+i*200, 300, 130, True) for i,t in enumerate(["训练数据与目标\n合理切分数据", "训练得到模型\n调整可学习参数", "模型处理新数据\n检查泛化与错误"])]
    for nodes in (left, right):
        for a,b in zip(nodes, nodes[1:]): d.arrow(a,b)
    d.text("验证集帮助选方案；独立测试集用于最后评估。\n训练高分不能替代上线后的可靠性。", 40, 860, 680, 100, 23)
    d.footer("两者常组合使用；训练过程本身也是程序。")
    yield d
    yield chain("06-transformer", "从文本到下一段文本", "常见自回归生成流程的教学简化", [
        "分词与编码\n文本变成 token 序列", "嵌入与位置信息\n变成可计算的表示",
        "多层模型变换\n注意力、前馈网络等组件", "输出分布\n估计后续 token 的可能性",
        "选择并输出 token\n加入上下文，继续下一步"], "反复生成直到满足停止条件；训练与生成不同。")
    yield chain("07-rag-agent", "生成只是系统的一部分", "检索、生成、执行与核验需要明确边界", [
        "用户问题与任务范围\n确认输入、目标和预算", "有权限的资料检索\n来源、版本、相关片段",
        "模型生成候选回答或动作\n依据上下文组织结果", "执行前检查\n工具权限、参数与必要确认",
        "工具执行与状态记录\n读取真实结果，支持恢复", "结果核验与交付\n引用、计算、测试及失败说明"], "外部资料里的文字，不自动拥有系统指令权限。")
    d = Diagram("08-tradeoffs", "模型成为产品的六项取舍", "先固定任务和质量标准，再比较方案", 1040)
    labels=["质量\n真实任务成功率", "延迟\n用户等待多久", "资源\n内存、电池、带宽", "数据边界\n来源、权限与隐私", "离线与可用性\n断网或故障怎么办", "维护成本\n更新、测试与恢复"]
    for i,t in enumerate(labels):
        d.box(t, 40+(i%2)*380, 190+(i//2)*240, 300, 160, blue=bool(i%2))
    d.footer("没有脱离场景的万能最优模型或端云分工。")
    yield d


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--render", action="store_true")
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--drawio", default=shutil.which("drawio") or shutil.which("draw.io") or
                        ("C:/Program Files/draw.io/draw.io.exe" if os.name == "nt" else "drawio"))
    args = parser.parse_args()
    out = ROOT / "build/diagram-preview" if args.preview else ASSETS
    if args.render: out.mkdir(parents=True, exist_ok=True)
    for diagram in diagrams():
        src = diagram.save()
        if not args.render: continue
        dest = out / (diagram.name + ".png")
        cmd = [args.drawio, "-x", "-f", "png", "--width", "1200", "--border", "20", "-o", str(dest), str(src), "--disable-gpu"]
        if not args.preview: cmd.insert(2, "-e")
        subprocess.run(cmd, check=True, timeout=90,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        # Repair the known truncated-IEND issue in embedded PNG exports.
        data = dest.read_bytes()
        iend = b"\x00\x00\x00\x00IEND\xaeB`\x82"
        if not data.endswith(iend):
            if not data.endswith(b"\x00\x00\x00\x00"): raise ValueError(f"Invalid PNG end: {dest}")
            dest.write_bytes(data[:-4] + iend)
        # Recompress IDAT without changing pixels or dropping embedded diagram metadata.
        data = dest.read_bytes()
        chunks = []
        pos = 8
        while pos < len(data):
            size = struct.unpack(">I", data[pos:pos+4])[0]
            chunks.append((data[pos+4:pos+8], data[pos+8:pos+8+size]))
            pos += size + 12
        packed = zlib.compress(zlib.decompress(b"".join(value for tag, value in chunks if tag == b"IDAT")), 9)
        rebuilt = bytearray(data[:8])
        written = False
        for tag, value in chunks:
            if tag == b"IDAT":
                if written: continue
                value = packed
                written = True
            rebuilt += struct.pack(">I", len(value)) + tag + value + struct.pack(">I", zlib.crc32(tag + value) & 0xffffffff)
        if len(rebuilt) < len(data): dest.write_bytes(rebuilt)
        print(dest.relative_to(ROOT), dest.stat().st_size, "bytes", flush=True)


if __name__ == "__main__":
    main()
