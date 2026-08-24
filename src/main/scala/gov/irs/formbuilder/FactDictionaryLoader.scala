package gov.irs.formbuilder

import gov.irs.factgraph.FactDictionary
import java.io.File
import scala.xml.{ Elem, NodeBuffer }

/** Merges the XML files in an app's `facts/` directory into one `<FactDictionaryModule>` and builds the
  * `FactDictionary` from it.
  *
  * Read from disk rather than the classpath, for the reason `FormBuilder.regenerate` documents. Callers get both the
  * built dictionary and the merged XML, because the generators need the raw XML too.
  */

case class LoadedFactDictionary(factDictionary: FactDictionary, xml: Elem)

def loadFactXml(app: FormBuilderApp): Elem = {
  val factDirectoryPath = app.factsDir
  val factsDirectory = new File(factDirectoryPath.toString)
  // Sorted for determinism: a duplicate `<Fact path="...">` across files is last-wins, and `File.listFiles` order is
  // undefined and varies by OS.
  val listOfFiles = if (factsDirectory.exists && factsDirectory.isDirectory) {
    factsDirectory.listFiles.filter(_.isFile).filter(_.getName.endsWith(".xml")).toList.sortBy(_.getName)
  } else {
    List.empty[File]
  }

  val facts = new NodeBuffer()
  for (file <- listOfFiles) {
    val fileName = file.getName()
    val factsFile = os.read(factDirectoryPath / fileName)
    val factXmlNodes = xml.XML.loadString(factsFile)
    val factNodes = factXmlNodes \ "Facts" \ "_"
    facts ++= factNodes
  }

  <FactDictionaryModule>
    <Facts>
      {facts}
    </Facts>
  </FactDictionaryModule>
}

def loadFactDictionary(app: FormBuilderApp): LoadedFactDictionary = {
  val factXml = loadFactXml(app)
  val factDictionary = FactDictionary.fromXml(factXml)
  LoadedFactDictionary(factDictionary, factXml)
}
