package zio.shield

import java.nio.file.Path

import scalafix.internal.v1.Rules
import scalafix.lint.RuleDiagnostic
import scalafix.v1._
import zio.shield.config.Config
import zio.shield.flow._
import zio.shield.rules._

import scala.collection.mutable

trait SemanticDocumentLoader {
  def load(synDoc: SyntacticDocument,
           path: Path): Either[Throwable, SemanticDocument]
}

class ZioShield private (val loader: SemanticDocumentLoader) {

  val flowCache: FlowCache = FlowCache.empty

  private[shield] def apply(syntacticRules: List[Rule] = List.empty,
                            semanticRules: List[Rule] = List.empty,
                            semanticZioShieldRules: List[
                              FlowCache => Rule with FlowInferenceDependent] =
                              List.empty): ConfiguredZioShield =
    new ConfiguredZioShield(
      this,
      Rules(syntacticRules),
      Rules(semanticRules ++ semanticZioShieldRules.map(_(flowCache))))

  def withExcluded(
      excludedRules: List[String] = List.empty,
      excludedInferrers: List[String] = List.empty): ConfiguredZioShield =
    apply(
      List.empty,
      ZioShield.allSemanticRules.filterNot {
        case flowDependent: FlowInferenceDependent =>
          excludedInferrers.exists(ei =>
            flowDependent.dependsOn.exists(_.name == ei)) ||
            excludedRules.contains(flowDependent.name.value)
        case r => excludedRules.contains(r.name.value)
      }
    )

  def withAllRules(): ConfiguredZioShield =
    apply(List.empty, ZioShield.allSemanticRules, ZioShield.allZioShieldRules)

  def withConfig(config: Config): ConfiguredZioShield =
    withExcluded(config.excludedRules, config.excludedInferrers)
}

class ConfiguredZioShield(val zioShield: ZioShield,
                          val syntacticRules: Rules,
                          val semanticRules: Rules) {

  private val synDocs: mutable.Map[Path, SyntacticDocument] =
    mutable.HashMap.empty
  private val semDocs: mutable.Map[Path, SemanticDocument] =
    mutable.HashMap.empty

  lazy val inferrers: List[FlowInferrer[_]] =
    semanticRules.rules
      .collect {
        case flowDependent: FlowInferenceDependent => flowDependent.dependsOn
      }
      .flatten
      .distinct

  def updateCache(files: List[Path])(
      onDiagnostic: ZioShieldDiagnostic => Unit): Unit = {
    val inputs = files.map(meta.Input.File(_))

    inputs.foreach { i =>
      synDocs.update(i.path.toNIO, SyntacticDocument.fromInput(i))
    }

    inputs.foreach { i =>
      val path = i.path.toNIO
      val synDoc = synDocs(path)
      zioShield.loader.load(
        synDoc,
        i.path.toNIO,
      ) match {
        case Left(err) =>
          onDiagnostic(ZioShieldDiagnostic.SemanticFailure(path, err))
        case Right(doc) => semDocs.update(path, doc)
      }
    }

    zioShield.flowCache.update(
      files.flatMap(f => semDocs.get(f).map(f -> _)).toMap)
    zioShield.flowCache.deepInferAndCache(inferrers)(files)
  }

  def cacheStats: FlowCache.Stats = zioShield.flowCache.stats

  def run(files: List[Path])(
      onDiagnostic: ZioShieldDiagnostic => Unit): Unit = {

    val inputs = files.map(meta.Input.File(_))

    def lint(path: Path, msg: RuleDiagnostic): ZioShieldDiagnostic =
      ZioShieldDiagnostic.Lint(path, msg.position, msg.message)

    def patch(oldDoc: String,
              newDoc: String,
              path: Path): Option[ZioShieldDiagnostic] =
      if (oldDoc != newDoc) {
        Some(ZioShieldDiagnostic.Patch(path, oldDoc, newDoc))
      } else {
        None
      }

    inputs.foreach { input =>
      val path = input.path.toNIO

      synDocs.get(path) match {
        case Some(synDoc) =>
          val (newDoc, msgs) =
            syntacticRules.syntacticPatch(synDoc, suppress = false)
          patch(input.text, newDoc, path).foreach(onDiagnostic)
          msgs.foreach(m => onDiagnostic(lint(path, m)))
        case None =>
      }

      semDocs.get(path) match {
        case Some(semDoc) =>
          val (newDoc, msgs) =
            semanticRules.semanticPatch(semDoc, suppress = false)
          patch(input.text, newDoc, path).foreach(onDiagnostic)
          msgs.foreach(m => onDiagnostic(lint(path, m)))
        case None =>
      }
    }
  }
}

object ZioShield {

  def apply(loader: SemanticDocumentLoader): ZioShield = new ZioShield(loader)

  val allSemanticRules = List(ZioShieldNoFutureMethods,
                              ZioShieldNoIgnoredExpressions,
                              ZioShieldNoReflection,
                              ZioShieldNoTypeCasting)

  val allZioShieldRules: List[FlowCache => Rule with FlowInferenceDependent] =
    List(
      new ZioShieldNoImpurity(_),
      new ZioShieldNoIndirectUse(_),
      new ZioShieldNoNull(_),
      new ZioShieldNoPartial(_)
    )
}
