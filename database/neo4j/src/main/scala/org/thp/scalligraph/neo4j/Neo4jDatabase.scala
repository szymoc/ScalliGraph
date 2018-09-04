package org.thp.scalligraph.neo4j

import java.nio.file.{Files, Paths}
import java.util.Date

import scala.reflect.ClassTag
import scala.util.Try

import play.api.Configuration

import gremlin.scala._
import javax.inject.Singleton
import org.apache.tinkerpop.gremlin.neo4j.structure.Neo4jGraph
import org.apache.tinkerpop.gremlin.structure.Graph
import org.neo4j.graphdb.Label
import org.neo4j.io.fs.FileUtils
import org.neo4j.tinkerpop.api.impl.Neo4jGraphAPIImpl
import org.thp.scalligraph.models._
import org.thp.scalligraph.{Config, Retry}

@Singleton
class Neo4jDatabase(graph: Neo4jGraph, maxRetryOnConflict: Int) extends Database {

  def this(dbPath: String, maxRetryOnConflict: Int) = this(Neo4jGraph.open(dbPath), maxRetryOnConflict)

  def this(configuration: Configuration) =
    this(
      Neo4jGraph.open(new Config(configuration)),
      configuration.getOptional[Int]("database.maxRetryOnConflict").getOrElse(5)
    )

  def this() =
    this(
      Configuration(
        Neo4jGraph.CONFIG_DIRECTORY → {
          val dbDir      = s"db-${math.random}"
          val targetPath = Paths.get("target")
          val dbPath     = targetPath.resolve(dbDir)
          Try(FileUtils.deletePathRecursively(dbPath))
          Try(Files.createDirectory(targetPath))
          Try(Files.createDirectory(dbPath))
          s"target/$dbDir"
        },
        Neo4jGraph.CONFIG_MULTI_PROPERTIES → true,
        Neo4jGraph.CONFIG_META_PROPERTIES  → true
      ))

  override def noTransaction[A](body: Graph ⇒ A): A = graph.synchronized {
    body(graph)
  }

  override def transaction[A](body: Graph ⇒ A): A = Retry(maxRetryOnConflict) {
    graph.synchronized {
      val tx = graph.tx
      tx.open()
      logger.debug(s"[$tx] Begin of transaction")
      try {
        val a = body(graph)
        tx.commit()
        a
      } catch {
        case e: Throwable ⇒
          Try(tx.rollback())
          throw e
      } finally {
        logger.debug(s"[$tx] End of transaction")
        tx.close()
      }
    }
  }

  override def createSchema(models: Seq[Model]): Unit =
    // Cypher can't be used here to create schema as it is not compatible with scala 2.12
    // https://github.com/neo4j/neo4j/issues/8832
    transaction { _ ⇒
      val neo4jGraph = graph.getBaseGraph.asInstanceOf[Neo4jGraphAPIImpl].getGraphDatabase
      for {
        model ← models
        _ = neo4jGraph
          .schema()
          .constraintFor(Label.label(model.label))
          .assertPropertyIsUnique("_id")
          .create()
        (indexType, properties) ← model.indexes
      } {
        if (properties.size != 1)
          logger.error(s"Neo4j index can contain only one property, found ${properties.size} for ${model.label}:${properties.mkString(",")}")
        properties.headOption.foreach { property ⇒
          indexType match {
            case IndexType.standard ⇒
              neo4jGraph
                .schema()
                .indexFor(Label.label(model.label))
                .on(property)
                .create()
            // graph.cypher(s"CREATE INDEX ON: ${model.label}(${properties.mkString(",")})")
            case IndexType.unique ⇒
              neo4jGraph
                .schema()
                .constraintFor(Label.label(model.label))
                .assertPropertyIsUnique(property)
                .create()
            // graph.cypher(s"CREATE CONSTRAINT ON (${model.label}:${properties.head}) ASSERT ${model.label}.${properties.head} IS UNIQUE")
            case IndexType.fulltext ⇒
              logger.error(s"Neo4j doesn't support fulltext index, fallback to standard index")
              neo4jGraph
                .schema()
                .constraintFor(Label.label(model.label))
                .assertPropertyIsUnique(property)
                .create()
            // graph.cypher(s"CREATE INDEX ON: ${model.label}(${properties.mkString(",")})")
          }
        }
      }
    }

  val dateMapping: SingleMapping[Date, Long] = SingleMapping[Date, Long](classOf[Long], d ⇒ Some(d.getTime), new Date(_))

  def fixMapping[M <: Mapping[_, _, _]](mapping: M): M =
    mapping match {
      case m if m == UniMapping.dateMapping          ⇒ dateMapping.asInstanceOf[M]
      case m if m == UniMapping.dateMapping.optional ⇒ dateMapping.optional.asInstanceOf[M]
      case m if m == UniMapping.dateMapping.sequence ⇒ dateMapping.sequence.asInstanceOf[M]
      case m if m == UniMapping.dateMapping.set      ⇒ dateMapping.set.asInstanceOf[M]
      case m                                         ⇒ m
    }

  override def getSingleProperty[D, G](element: Element, key: String, mapping: SingleMapping[D, G]): D =
    super.getSingleProperty(element, key, fixMapping(mapping))

  override def getOptionProperty[D, G](element: Element, key: String, mapping: OptionMapping[D, G]): Option[D] =
    super.getOptionProperty(element, key, fixMapping(mapping))

  override def getListProperty[D, G](element: Element, key: String, mapping: ListMapping[D, G]): Seq[D] =
    element
      .value[Array[G]](key)
      .map(fixMapping(mapping).toDomain)

  // super.getListProperty(element, key, fixMapping(mapping))

  override def getSetProperty[D, G](element: Element, key: String, mapping: SetMapping[D, G]): Set[D] =
    element
      .value[Array[G]](key)
      .map(fixMapping(mapping).toDomain)
      .toSet

  // super.getSetProperty(element, key, fixMapping(mapping))

  override def setListProperty[D, G](element: Element, key: String, values: Seq[D], mapping: ListMapping[D, _]): Unit = {
    element.property(key, values.flatMap(fixMapping(mapping).toGraphOpt).toArray(ClassTag(mapping.graphTypeClass)))
    ()
    // super.setListProperty(element, key, values, fixMapping(mapping))
  }

  override def setSetProperty[D, G](element: Element, key: String, values: Set[D], mapping: SetMapping[D, _]): Unit = {
    element.property(key, values.flatMap(fixMapping(mapping).toGraphOpt).toArray(ClassTag(mapping.graphTypeClass)))
    ()
    // super.setSetProperty(element, key, values, fixMapping(mapping))
  }

  override def setSingleProperty[D, G](element: Element, key: String, value: D, mapping: SingleMapping[D, _]): Unit =
    super.setSingleProperty(element, key, value, fixMapping(mapping))

  override def setOptionProperty[D, G](element: Element, key: String, value: Option[D], mapping: OptionMapping[D, _]): Unit =
    super.setOptionProperty(element, key, value, fixMapping(mapping))
}