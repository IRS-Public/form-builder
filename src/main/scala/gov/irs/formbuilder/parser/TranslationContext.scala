// Builds the dotted keys the generated flow locale file is keyed by, and collects the authored
// text under them as the parser walks. Every context in one parse shares a single mutable map, and
// an instance is a path into that map plus the counters used to name unnamed children.
//
// KEYS MUST STAY STABLE BETWEEN BUILDS. The translated locale files are keyed by them, and
// syncTranslationLocales drops any key that no longer exists, discarding its translations.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.mutable

case class TranslationContext(
    translationMap: mutable.LinkedHashMap[String, Any] = mutable.LinkedHashMap.empty,
    translationContext: List[String] = List.empty,
    tagCounts: mutable.Map[String, Int] = mutable.Map.empty,
    // Shared by every context in one parse, unlike tagCounts, which counts within one parent. See
    // the forChildWithId overload that takes a signature.
    idSignatures: mutable.Map[String, String] = mutable.Map.empty,
    // Shared the same way, and keyed by page. See claimControlId.
    controlIdCounts: mutable.Map[String, Int] = mutable.Map.empty,
) {
  def forChildWithoutUniqueId(label: String): TranslationContext = {
    val childKey = nextChildKey(label)
    val currentMap = translationMap.getMap(translationContext)
    currentMap.getOrElseUpdate(childKey, mutable.LinkedHashMap.empty[String, Any])
    TranslationContext(
      translationMap,
      translationContext :+ childKey,
      idSignatures = idSignatures,
      controlIdCounts = controlIdCounts,
    )
  }

  def forChildWithoutUniqueId(label: String, uniqueContent: String): TranslationContext = {
    val childKey = getHashKey(label, uniqueContent)
    val currentMap = translationMap.getMap(translationContext)
    currentMap.getOrElseUpdate(childKey, mutable.LinkedHashMap.empty[String, Any])
    TranslationContext(
      translationMap,
      translationContext :+ childKey,
      idSignatures = idSignatures,
      controlIdCounts = controlIdCounts,
    )
  }

  def forChildWithId(id: String): TranslationContext = {
    val currentMap = translationMap.getMap(translationContext)
    currentMap.getOrElseUpdate(id, mutable.LinkedHashMap.empty[String, Any])
    TranslationContext(
      translationMap,
      translationContext :+ id,
      idSignatures = idSignatures,
      controlIdCounts = controlIdCounts,
    )
  }

  /** A child keyed by an author-supplied id, with a signature as the tiebreak.
    *
    * One page may ask about the same fact more than once — two conditional phrasings of a question, or one question
    * whose answer options are worded differently for a joint return, with only one showing at a time. For `<fg-set>`
    * the id is the fact path, so every phrasing would land on one key and the second would collide with the first in
    * [[updateValue]].
    *
    * `signature` is the authored text that decides whether two children are the same child: equal signatures share a
    * key, and so share their translations rather than duplicating them. The first signature to claim an id keeps the
    * bare id — a flow with no repeats has exactly the keys it had before this existed — and a later, different one gets
    * `id-<hash of signature>`.
    *
    * The hash is of the signature rather than a counter, so editing one phrasing moves only that phrasing's key. Which
    * of two repeats holds the bare id does depend on document order, the same way children named by [[nextChildKey]]
    * already do.
    */
  def forChildWithId(id: String, signature: String): TranslationContext = {
    val fullId = fullKey(id)
    val claimed = idSignatures.getOrElseUpdate(fullId, signature)
    forChildWithId(if (claimed == signature) id else getHashKey(id, signature))
  }

  def nextChildKey(label: String) = {
    val count = (tagCounts.getOrElse(label, -1)) + 1
    tagCounts(label) = count
    s"$label-$count"
  }

  def getHashKey(label: String, content: String): String = {
    val digest = MessageDigest.getInstance("MD5")
    val hexString = digest.digest(content.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString
    // updateValue raises on a collision, and the fix is to lengthen this truncation.
    s"$label-${hexString.take(6)}"
  }

  def updateValue(key: String, value: String): Unit = {
    val currentMap = translationMap.getMap(translationContext)
    if (currentMap.contains(key)) {
      val existingContent = currentMap.get(key)
      val contentString = existingContent match {
        case Some(value) => value.toString
        case None        => throw new Exception("Expected a string value")
      }
      if (contentString != value) {
        throw IllegalArgumentException(
          s"Collision detected. Expected unique translation key: \"$key\", but an entry with a matching key and differing content already existed. To resolve this error you may want to increase the number of characters returned by getHashKey.",
        )
      }
    }
    currentMap += key -> value
  }

  def fullKey(): String = {
    translationContext.mkString(".")
  }

  /** The last segment of this context's key: the id this child ended up claiming. */
  def localKey: String = translationContext.lastOption.getOrElse("")

  /** A DOM id built from `base`, unique within the page.
    *
    * Translation keys and DOM ids want different things from a repeated question. A page may hold the same `<fg-set>`
    * twice — two conditional branches asking it in the same words, only one of which shows — and there sharing a
    * translation key is right: it is one string, translated once. Sharing an `id` is not: `<label for>` binds to the
    * first element with that id, so the second copy's labels would point into the first, which is hidden. So the first
    * claim keeps the bare id and later ones get `-2`, `-3`.
    *
    * Scoped to the page, because that is the document the ids must be unique in — the first segment of the key path is
    * the page's route, since [[Page]] is what opens a context under the flow root.
    */
  def claimControlId(base: String): String = {
    val key = s"${translationContext.headOption.getOrElse("")}\u0000$base"
    val count = controlIdCounts.getOrElse(key, 0) + 1
    controlIdCounts(key) = count
    if (count == 1) base else s"$base-$count"
  }

  def fullKey(localKey: String): String = {
    if (translationContext.isEmpty) localKey else s"${translationContext.mkString(".")}.$localKey"
  }
}

extension (translationMap: mutable.LinkedHashMap[String, Any]) {
  def getMap(keys: List[String]): mutable.LinkedHashMap[String, Any] = {
    val output = keys.foldLeft(Option(translationMap: Any)) {
      case (Some(m: mutable.LinkedHashMap[String, Any] @unchecked), key) => m.get(key)
      case _ => throw new IllegalArgumentException("invalid key path to translation map")
    }
    output.get match
      case m: mutable.LinkedHashMap[String, Any] @unchecked => m
      case _                                                =>
        throw new IllegalArgumentException(
          s"expected value to be of type mutable.LinkedHashMap[String, Any], but was ${output.get.getClass.getName}",
        )
  }
}
