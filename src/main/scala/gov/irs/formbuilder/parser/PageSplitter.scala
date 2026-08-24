// Rewrites the page list for --singleQuestionPerScreen. Every authored Page becomes one or more
// emitted Pages, each carrying `sourcePageRoute` so the stepper can still group them.
// Runs after parsing and before locale generation, on the parsed tree rather than on XML.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import scala.collection.mutable

/** A page with `group-by="h3"` is cut along its top-level headings instead. A page with no question passes through
  * unchanged, with `sourcePageRoute` set.
  */
object PageSplitter {

  def split(pages: List[Page]): List[Page] = pages.flatMap(splitPage)

  private def splitPage(page: Page): List[Page] = page.groupBy match {
    case Some("h3") => splitByH3(page)
    case _          => splitPerQuestion(page)
  }

  private def splitPerQuestion(page: Page): List[Page] = {
    val modals = collectModals(page.children)
    val flat = page.children.iterator.flatMap(n => flatten(n, None)).toVector
    val questionIndices = flat.zipWithIndex.collect {
      case (_: FgSet, i)        => i
      case (_: FgCollection, i) => i
    }

    if (questionIndices.isEmpty) {
      return List(page.copy(sourcePageRoute = Some(page.route)))
    }

    val firstQ = questionIndices.head
    val intro = flat.take(firstQ).filterNot(_.isInstanceOf[Modal])
    val keepOriginalRoute = questionIndices.size == 1

    val emitted = mutable.ListBuffer.empty[Page]
    for (i <- questionIndices.indices) {
      val qIdx = questionIndices(i)
      val nextIdx = if (i == questionIndices.size - 1) flat.length else questionIndices(i + 1)
      val sliceRaw = flat.slice(qIdx, nextIdx).filterNot(_.isInstanceOf[Modal])
      val withIntro = if (i == 0) intro ++ sliceRaw else sliceRaw
      val children: Seq[FlowNode] = Seq(Section(withIntro)) ++ modals

      val question = flat(qIdx)
      val route =
        if (keepOriginalRoute) page.route
        else joinRoute(page.route, slugFor(question))

      emitted += Page(
        translationContext = page.translationContext,
        route = route,
        exclude = page.exclude,
        children = children,
        sourcePageRoute = Some(page.route),
        module = page.module,
      )
    }
    emitted.toList
  }

  /** Cut the page along its top-level `<h3>` siblings, one emitted Page per heading. Content before the first heading
    * attaches to the first emitted page.
    */
  private def splitByH3(page: Page): List[Page] = {
    val modals = collectModals(page.children)
    val flat = page.children.iterator
      .flatMap(n => flatten(n, None))
      .filterNot(_.isInstanceOf[Modal])
      .toVector

    val h3Indices = flat.zipWithIndex.collect {
      case (h: HtmlLeafNode, i) if h.htmlElement.label == "h3" => i
    }

    if (h3Indices.isEmpty) return splitPerQuestion(page)

    val intro = flat.take(h3Indices.head)
    val groupSlices: Seq[Vector[FlowNode]] = h3Indices.zipWithIndex.map { case (start, gi) =>
      val end = if (gi == h3Indices.size - 1) flat.length else h3Indices(gi + 1)
      flat.slice(start, end)
    }

    // A group with no question (a closing summary and its knockout alerts, say) merges into the previous group that
    // has one, so its knockouts still fire on Next from the last question.
    val merged = mutable.ListBuffer.empty[Vector[FlowNode]]
    groupSlices.foreach { slice =>
      val hasQuestion = slice.exists(n => n.isInstanceOf[FgSet] || n.isInstanceOf[FgCollection])
      if (hasQuestion || merged.isEmpty) merged += slice
      else merged(merged.size - 1) = merged.last ++ slice
    }

    if (merged.isEmpty) return splitPerQuestion(page)

    merged(0) = intro ++ merged(0)

    merged.zipWithIndex.map { case (groupNodes, i) =>
      val children: Seq[FlowNode] = Seq(Section(groupNodes)) ++ modals
      val route = joinRoute(page.route, h3SlugForGroup(groupNodes, i))
      Page(
        translationContext = page.translationContext,
        route = route,
        exclude = page.exclude,
        children = children,
        sourcePageRoute = Some(page.route),
        module = page.module,
      )
    }.toList
  }

  private def h3SlugForGroup(group: Seq[FlowNode], idx: Int): String = {
    val firstH3 = group.collectFirst {
      case h: HtmlLeafNode if h.htmlElement.label == "h3" => h
    }
    firstH3
      .map(h => slugFromHeadingText(h.htmlElement.child.mkString))
      .filter(_.nonEmpty)
      .getOrElse(s"group-${idx + 1}")
  }

  private def slugFromHeadingText(raw: String): String = {
    val noTags = raw.replaceAll("<[^>]+>", " ")
    val alphaSpace = noTags.replaceAll("[^A-Za-z0-9\\s]", " ").trim
    if (alphaSpace.isEmpty) ""
    else alphaSpace.toLowerCase.split("\\s+").filter(_.nonEmpty).take(4).mkString("-")
  }

  /** Flatten Section and FgDetail wrappers into a linear sequence, propagating an FgDetail's condition down to any
    * contained question that declares none of its own.
    */
  private def flatten(node: FlowNode, inherited: Option[Condition]): Seq[FlowNode] = node match {
    case s: Section  => s.children.flatMap(c => flatten(c, inherited))
    case d: FgDetail =>
      val effective = d.condition.orElse(inherited)
      d.children.flatMap(c => flatten(c, effective))
    case fg: FgSet if inherited.isDefined && fg.condition.isEmpty =>
      Seq(fg.copy(condition = inherited))
    case fg: FgCollection if inherited.isDefined && fg.condition.isEmpty =>
      Seq(fg.copy(condition = inherited))
    case _ => Seq(node)
  }

  private def collectModals(nodes: Seq[FlowNode]): Seq[Modal] =
    nodes.flatMap {
      case m: Modal    => Seq(m)
      case s: Section  => collectModals(s.children)
      case d: FgDetail => collectModals(d.children)
      case _           => Seq.empty
    }

  private def slugFor(node: FlowNode): String = node match {
    case fg: FgSet        => kebab(lastPathSegment(fg.path))
    case fg: FgCollection => kebab(lastPathSegment(fg.path))
    case _                => throw new IllegalStateException(s"slugFor called on non-question node: $node")
  }

  private def lastPathSegment(path: String): String = {
    val parts = path.split("/").filter(s => s.nonEmpty && s != "*")
    if (parts.isEmpty) path.stripPrefix("/") else parts.last
  }

  private def kebab(camel: String): String = camel
    .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
    .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
    .toLowerCase

  private def joinRoute(parent: String, child: String): String =
    if (parent == "/") s"/$child" else s"$parent/$child"
}
