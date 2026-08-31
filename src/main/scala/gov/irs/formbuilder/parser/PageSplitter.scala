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
      // No question at the top level does not mean no question. A transpiled flow wraps each of its
      // source screens in a conditional block — `<div class="df-screen" condition="…">` — and
      // `flatten` deliberately does not look through one: the condition that decides whether the
      // screen shows at all lives in that element's own attributes rather than in a parsed field, so
      // flattening it away would leave every question inside it unconditional. Cutting *between* the
      // blocks keeps each wrapper whole, which is both correct and the right grain — one emitted page
      // per source screen.
      //
      // Whether a block *has* a question is beside the point, and reading it as the trigger was a bug:
      // a screen of pure prose — a breather, an outcome — is still a screen, and its wrapper still
      // carries the condition that decides whether it shows. Left unsplit it kept the page's route,
      // took no gate, and rendered as a page with every element on it hidden.
      //
      // Every unit has to be a wrapper for this to be the transpiled shape. `topLevelUnits` sees
      // through `<section>` but hands back whatever else is there, so a page of loose `<p>`s would
      // otherwise be cut into one page per paragraph.
      val blocks = topLevelUnits(page.children).filterNot(_.isInstanceOf[Modal]).toVector
      if (blocks.nonEmpty && blocks.forall(_.isInstanceOf[HtmlWithChildren])) {
        return splitByBlocks(page, blocks, modals)
      }
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

  /** Cut the page between its top-level blocks, one emitted Page per block.
    *
    * The counterpart to [[splitPerQuestion]] for a flow whose questions are nested inside wrapper elements rather than
    * sitting beside each other. Each block is emitted whole — tags, attributes and all — so a condition on the wrapper
    * still gates exactly what it gated before.
    *
    * Every block becomes a page, whether or not it has a question — a block with prose and no question is a screen
    * someone wrote to be read. Nothing is merged, and that is the difference from [[splitByH3]]: the reason that one
    * merges is to keep a question-less group from rendering as a page with nothing on it, and here the block's own
    * condition answers that instead. It becomes the page's gate, so a block that does not apply is skipped by the
    * navigator rather than shown empty.
    */
  private def splitByBlocks(page: Page, blocks: Vector[FlowNode], modals: Seq[Modal]): List[Page] = {
    val keepOriginalRoute = blocks.size == 1
    val used = mutable.Set.empty[String]

    blocks.zipWithIndex.map { case (block, i) =>
      val children: Seq[FlowNode] = Seq(Section(Seq(block))) ++ modals
      val route =
        if (keepOriginalRoute) page.route
        else {
          // Two screens on one page can name the same fact or carry the same heading, so the slug is
          // made unique here rather than emitting a duplicate route the generator would overwrite.
          val base = blockSlug(Seq(block), i)
          val slug = if (used.add(base)) base else Iterator.from(2).map(n => s"$base-$n").find(used.add).get
          joinRoute(page.route, slug)
        }
      Page(
        translationContext = page.translationContext,
        route = route,
        exclude = page.exclude,
        children = children,
        sourcePageRoute = Some(page.route),
        module = page.module,
        gate = block match {
          case h: HtmlWithChildren => h.condition
          case _                   => None
        },
      )
    }.toList
  }

  /** A block's own name: its heading if it has one, else the fact its first question writes. */
  private def blockSlug(group: Seq[FlowNode], idx: Int): String =
    headingIn(group)
      .map(h => slugFromHeadingText(h.htmlElement.child.mkString))
      .filter(_.nonEmpty)
      .orElse(questionIn(group).map(slugFor))
      .getOrElse(s"screen-${idx + 1}")

  private def headingIn(nodes: Seq[FlowNode]): Option[HtmlLeafNode] = nodes.iterator.flatMap {
    case h: HtmlLeafNode if Set("h2", "h3", "h4").contains(h.htmlElement.label) => Iterator(h)
    case h: HtmlWithChildren                                                    => headingIn(h.children).iterator
    case s: Section                                                             => headingIn(s.children).iterator
    case _                                                                      => Iterator.empty
  }.nextOption()

  private def questionIn(nodes: Seq[FlowNode]): Option[FlowNode] = nodes.iterator.flatMap {
    case fg: FgSet           => Iterator(fg: FlowNode)
    case fg: FgCollection    => Iterator(fg: FlowNode)
    case h: HtmlWithChildren => questionIn(h.children).iterator
    case s: Section          => questionIn(s.children).iterator
    case d: FgDetail         => questionIn(d.children).iterator
    case a: FgAlert          => questionIn(a.children).iterator
    case _                   => Iterator.empty
  }.nextOption()

  /** A page's children with `<section>` wrappers seen through, so a real block is a unit and a grouping element is not. */
  private def topLevelUnits(nodes: Seq[FlowNode]): Seq[FlowNode] = nodes.flatMap {
    case s: Section => topLevelUnits(s.children)
    case other      => Seq(other)
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
    // Apostrophes are removed rather than turned into a separator, so "Let's get some basic" gives
    // `lets-get-some-basic` instead of `let-s-get-some` — an apostrophe is inside a word, not between
    // two. Both the straight and the typographic one, since authored prose uses the second.
    val noApostrophes = noTags.replaceAll("['\u2019]", "")
    val alphaSpace = noApostrophes.replaceAll("[^A-Za-z0-9\\s]", " ").trim
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
