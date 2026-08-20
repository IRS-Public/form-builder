package gov.irs.formbuilder

import gov.irs.factgraph.FactDictionary
import java.io.File
import scala.xml.{ Elem, NodeBuffer }

case class LoadedFactDictionary(factDictionary: FactDictionary, xml: Elem)

def loadFactXml(app: FormBuilderApp): Elem = {
  val factDirectoryPath = app.factsDir
  val factsDirectory = new File(factDirectoryPath.toString)
  // Deterministic order: duplicate `<Fact path="...">` from different files are merged
  // last-wins; `File.listFiles` order is undefined and varies by OS (see e.g. relationship on collection items).
  val listOfFiles = if (factsDirectory.exists && factsDirectory.isDirectory) {
    factsDirectory.listFiles.filter(_.isFile).filter(_.getName.endsWith(".xml")).toList.sortBy(_.getName)
  } else {
    List.empty[File]
  }

  val facts = new NodeBuffer()
  for (file <- listOfFiles) {
    val fileName = file.getName()
    // Read the file's content from the same on-disk directory we enumerated above (not via the
    // classpath). This keeps fact loading consistent with Author Mode, which patches these files on
    // disk and then calls `regenerate()` in-process: `Source.fromResource` would read the stale copy
    // that sbt cached under target/.../classes at build time until the next resource re-copy.
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
