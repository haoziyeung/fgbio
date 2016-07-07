/*
 * The MIT License
 *
 * Copyright (c) 2016 Fulcrum Genomics LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.fulcrumgenomics.bam

import com.fulcrumgenomics.FgBioDef._
import com.fulcrumgenomics.cmdline.{ClpGroups, FgBioTool}
import com.fulcrumgenomics.util.{Io, RScriptRunner}
import dagr.commons.util.LazyLogging
import dagr.sopt.{arg, clp}
import htsjdk.samtools.SamReaderFactory
import htsjdk.samtools.filter.DuplicateReadFilter
import htsjdk.samtools.reference.ReferenceSequenceFileFactory
import htsjdk.samtools.util.SamLocusIterator.LocusInfo
import htsjdk.samtools.util.{CollectionUtil, IntervalList, SamLocusIterator, SequenceUtil}
import htsjdk.variant.vcf.VCFFileReader

import scala.collection.JavaConversions.{asScalaIterator, iterableAsScalaIterable}
import scala.collection.mutable


@clp(group=ClpGroups.SamOrBam, description=
"""
  |Plots MAF by positions along the genome. Takes each position...
  |
  |Requires RScript and ggplot2
""")
class PlotMafByPosition
( @arg(flag="i", doc="The input SAM or BAM file.")       val input: PathToBam,
  @arg(flag="l", doc="The set of regions to analyze.")   val intervals: PathToIntervals,
  @arg(flag="o", doc="Prefix for output files.")         val output: PathPrefix,
  @arg(flag="r", doc="Reference fasta file.")            val ref: PathToFasta,
  @arg(flag="q", doc="Ignore bases below this quality.") val minQuality: Int = 20,
  @arg(flag="d", doc="Minimum coverage depth to emit a locus.") val minDepth: Int = 100,
  @arg(flag="D", doc="Downsample all sites to minDepth first.") val downsampleToMinDepth: Boolean = false,
  @arg(flag="v", doc="A VCF of known/expected variant sites.")  val variants: Option[PathToVcf],
  @arg(flag="n", doc="Name of the dataset, used in plotting.")  val name: Option[String] = None
) extends FgBioTool with LazyLogging {
  /** The field separator in the output file. */
  private val Sep = "\t"
  private val ScriptFile = "com/fulcrumgenomics/bam/plotMafByPosition.R"
  private val random = new java.util.Random(42)

  Io.assertReadable(Seq(input, intervals, ref))
  private val intervalList = IntervalList.fromFile(intervals.toFile).uniqued(false)

  override def execute(): Unit = {
    val textOut = output.getParent.resolve(output.getFileName + ".txt")
    val pdfOut  = output.getParent.resolve(output.getFileName + ".pdf")

    logger.info("Loading expected variants from VCF.")
    val expected = loadExpectedPositions

    logger.info("Traversing positions in BAM file.")
    val iter = buildLocusIterator
    var id = 0
    val refFile = ReferenceSequenceFileFactory.getReferenceSequenceFile(ref.toFile)
    val out = Io.toWriter(textOut)
    out.write(Seq("id", "chrom", "pos", "ref", "group", "depth", "ref_count", "non_ref_count", "non_ref_fraction").mkString(Sep))
    out.newLine()

    while (iter.hasNext) {
      val xs = iter.next()
      val refBase = refFile.getSubsequenceAt(xs.getSequenceName, xs.getPosition, xs.getPosition).getBases()(0)
      val (refCount, nonRefCount) = counts(xs, refBase)
      val total = refCount + nonRefCount
      val group = if (expected.contains(xs.getSequenceName + ":" + xs.getPosition)) "Expected" else "Unexpected"

      if (total >= minDepth) {
        val fields = Seq(id, xs.getSequenceName, xs.getPosition, refBase.toChar, group, total, refCount, nonRefCount, nonRefCount / total.toDouble)
        out.write(fields.mkString("\t"))
        out.newLine()
        id += 1
      }
    }

    out.close()
    iter.safelyClose()

    logger.info("Running R to generate plots.")
    RScriptRunner.run(ScriptFile, textOut, pdfOut, name.getOrElse(input.getFileName))
  }

  /** Loads up the set of expected variant positions from a VCF as "chrom:pos". */
  def loadExpectedPositions: Set[String] = variants match {
    case None => Set()
    case Some(vs) =>
      val in = new VCFFileReader(vs.toFile, true)
      val positions = new mutable.HashSet[String]()
      this.intervalList.getIntervals.foreach(i =>
        in.query(i.getContig, i.getStart, i.getEnd).foreach(v => {
          if (v.isVariant && v.isSNP && !v.isFiltered) positions += (v.getContig + ":" + v.getStart)
        })
      )

      in.safelyClose()
      positions.toSet
  }

  /** Builds a SAM locus iterator that filters by base quality score and duplicate status but nothing else. */
  def buildLocusIterator: SamLocusIterator = {
    val in = SamReaderFactory.make().open(input)
    val iter = new SamLocusIterator(in, this.intervalList)
    iter.setEmitUncoveredLoci(true)
    iter.setIncludeIndels(false)
    iter.setMaxReadsToAccumulatePerLocus(Int.MaxValue)
    iter.setQualityScoreCutoff(minQuality)
    iter.setSamFilters(CollectionUtil.makeList(new DuplicateReadFilter))
    iter
  }

  /** Calculates the ref and non-ref counts of bases at a locus, while only counting
    * at most one observation per queryname.
    */
  def counts(info: LocusInfo, refBase: Byte): (Int, Int) = {
    val seen = new mutable.HashSet[String]
    var refCount = 0
    var altCount = 0
    info.getRecordAndPositions.filterNot(x => SequenceUtil.isNoCall(x.getReadBase)).foreach(recAndOffset => {
      val name = recAndOffset.getRecord.getReadName
      if (!seen.contains(name)) {
        seen.add(name)
        if (SequenceUtil.basesEqual(refBase, recAndOffset.getReadBase)) refCount += 1
        else altCount += 1
      }
    })

    if (downsampleToMinDepth) {
      var (dsRef, dsAlt) = (refCount, altCount)
      while (dsRef + dsAlt > this.minDepth) {
        if (this.random.nextInt(dsRef + dsAlt) < dsAlt) dsAlt -= 1 else dsRef -= 1
      }

      (dsRef, dsAlt)
    }
    else {
      (refCount, altCount)
    }
  }
}
