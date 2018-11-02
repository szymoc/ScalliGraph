package org.thp.scalligraph.models

import java.util.Date

import gremlin.scala._
import gremlin.scala.dsl.{Converter, DomainRoot}
import org.thp.scalligraph.controllers.UpdateOps
import org.thp.scalligraph.macros.ModelMacro
import org.thp.scalligraph.services.AttachmentSrv
import org.thp.scalligraph.{FPath, NotFoundError}

import scala.annotation.StaticAnnotation
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.experimental.macros

class Readonly extends StaticAnnotation

class WithMapping[D, +G](mapping: Mapping[D, _, G]) extends StaticAnnotation

object IndexType extends Enumeration {
  val standard, unique, fulltext = Value
}
class DefineIndex(indexType: IndexType.Value, fields: String*) extends StaticAnnotation

trait HasModel[E <: Product] {
  val model: Model.Base[E]
}

trait HasVertexModel[E <: Product] extends HasModel[E] {
  val model: Model.Vertex[E]
}

trait HasEdgeModel[E <: Product, FROM <: Product, TO <: Product] extends HasModel[E] {
  val model: Model.Edge[E, FROM, TO]
}

trait Entity extends DomainRoot {
  val _id: String
  val _model: Model
  val _createdBy: String
  val _updatedBy: Option[String]
  val _createdAt: Date
  val _updatedAt: Option[Date]
}

object Model {
  type Base[E0 <: Product] = Model {
    type E = E0
  }
  type Vertex[E0 <: Product] = VertexModel {
    type E = E0
  }
  type Edge[E0 <: Product, FROM <: Product, TO <: Product] =
    EdgeModel[FROM, TO] {
      type E = E0
    }

  def vertex[E <: Product]: Vertex[E] = macro ModelMacro.mkVertexModel[E]

  def edge[E <: Product, FROM <: Product, TO <: Product]: Edge[E, FROM, TO] =
    macro ModelMacro.mkEdgeModel[E, FROM, TO]

  def printElement(e: Element): String =
    e +
      e.keys()
        .asScala
        .map(key ⇒
          s"\n - $key = ${e.properties[Any](key).asScala.map(_.value()).mkString("-,-")} (${e.properties[Any](key).asScala.toSeq.headOption.fold("empty")(_.value.getClass.toString)})")
        .mkString
}

abstract class Model {
  thisModel ⇒
  type E <: Product
  type EEntity = E with Entity
  type ElementType <: Element

  val label: String

  val indexes: Seq[(IndexType.Value, Seq[String])]

  def get(id: String)(implicit db: Database, graph: Graph): ElementType
  val fields: Map[String, Mapping[_, _, _]]
  def toDomain(element: ElementType)(implicit db: Database): EEntity

  def converter(db: Database, graph: Graph): Converter.Aux[EEntity, ElementType]

  /** Save all attachments (FFile) contained by an entity and transform them into FAttachment */
  def saveAttachment(attachmentSrv: AttachmentSrv, e: E)(implicit ec: ExecutionContext): Future[E]

  /** Save attachments from an update document. Replace all FFile by FAttachment */
  def saveUpdateAttachment(attachmentSrv: AttachmentSrv, updates: Map[FPath, UpdateOps.Type])(
      implicit ec: ExecutionContext): Future[Map[FPath, UpdateOps.Type]]
}

abstract class VertexModel extends Model { thisModel ⇒
  override type ElementType = Vertex

  def create(e: E)(implicit db: Database, graph: Graph): Vertex

  override def get(id: String)(implicit db: Database, graph: Graph): Vertex =
    graph.V().has(Key("_id") of id).headOption().getOrElse(throw NotFoundError(s"Vertex $id not found"))

  override def converter(db: Database, graph: Graph): Converter.Aux[EEntity, Vertex] =
    new Converter[EEntity] {
      override type GraphType = Vertex
      override def toDomain(v: Vertex): E with Entity = thisModel.toDomain(v)(db)
      override def toGraph(e: EEntity): Vertex        = thisModel.get(e._id)(db, graph)
    }
}

abstract class EdgeModel[FROM <: Product, TO <: Product] extends Model { thisModel ⇒
  override type ElementType = Edge

  val fromLabel: String
  val toLabel: String

  def create(e: E, from: Vertex, to: Vertex)(implicit db: Database, graph: Graph): Edge

  override def get(id: String)(implicit db: Database, graph: Graph): Edge =
    graph.E().has(Key("_id") of id).headOption().getOrElse(throw NotFoundError(s"Edge $id not found"))

  override def converter(db: Database, graph: Graph): Converter.Aux[EEntity, Edge] =
    new Converter[EEntity] {
      override type GraphType = Edge
      override def toDomain(e: Edge): E with Entity = thisModel.toDomain(e)(db)
      override def toGraph(e: EEntity): Edge        = get(e._id)(db, graph)
    }
}
