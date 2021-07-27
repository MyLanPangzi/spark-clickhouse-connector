package xenon.clickhouse.parse

import scala.collection.JavaConverters._

import org.antlr.v4.runtime.tree.ParseTree
import xenon.clickhouse.ClickHouseAstBaseVisitor
import xenon.clickhouse.spec.{TableEngineSpec, UnknownTableEngineSpec}
import xenon.clickhouse.ClickHouseAstParser._
import xenon.clickhouse.expr.{Expr, FieldExpr, FuncExpr, StringLiteral}

class AstVisitor extends ClickHouseAstBaseVisitor[AnyRef] {
  import ParseUtil._

  protected def typedVisit[T](ctx: ParseTree): T =
    ctx.accept(this).asInstanceOf[T]

  override def visitEngineClause(ctx: EngineClauseContext): TableEngineSpec = {
    val engineExpr = source(ctx.engineExpr)
    val engine = source(ctx.engineExpr.identifierOrNull)

    val engineArgs: Seq[Expr] =
      Option(ctx.engineExpr.columnExprList)
        .map(_.columnsExpr.asScala)
        .getOrElse(List.empty)
        .map(visitColumnsExpr)

    engine match {
      case eg: String if "MergeTree" equalsIgnoreCase eg =>
      case eg: String if "ReplicatedMergeTree" equalsIgnoreCase eg =>
      case eg: String if "ReplacingMergeTree" equalsIgnoreCase eg =>
      case eg: String if "ReplicatedReplacingMergeTree" equalsIgnoreCase eg =>
      case eg: String if "Distributed" equalsIgnoreCase eg =>
    }

    val orderByOpt = listToOption(ctx.orderByClause).map(source)
    val partOpt = listToOption(ctx.partitionByClause).map(_.columnExpr).map(visitColumnExpr)
    val pkOpt = listToOption(ctx.primaryKeyClause).map(_.columnExpr).map(visitColumnExpr)
    val sampleByOpt = listToOption(ctx.sampleByClause).map(_.columnExpr).map(visitColumnExpr)

    // we don't care about ttl now
    val ttlOpt = listToOption(ctx.ttlClause).map(source)

    val settingsOpt = listToOption(ctx.settingsClause).map(visitSettingsClause)

    println(s"engine expr: $engineExpr")
    println(s"engine: $engine")
    println(s"engine args: $engineArgs")
    println(s"order by: $orderByOpt")
    println(s"partition by: $partOpt")
    println(s"primary key: $pkOpt")
    println(s"sample by: $sampleByOpt")
    println(s"ttl: $ttlOpt")
    println(s"settings: $settingsOpt")

    UnknownTableEngineSpec(engineExpr)
  }

  ////////////////////////////////////////////////
  /////////////// visit ColumnExpr ///////////////
  ////////////////////////////////////////////////
  def visitColumnExpr(ctx: ColumnExprContext): Expr = ctx match {
    case fieldCtx: ColumnExprIdentifierContext => visitColumnExprIdentifier(fieldCtx)
    case literalCtx: ColumnExprLiteralContext => visitColumnExprLiteral(literalCtx)
    case funcCtx: ColumnExprFunctionContext => visitColumnExprFunction(funcCtx)
    case other: ColumnExprContext => throw new IllegalArgumentException(
        s"unsupported ColumnExpr: [${other.getClass.getSimpleName}] ${other.getText}"
      )
  }

  override def visitColumnExprIdentifier(ctx: ColumnExprIdentifierContext): FieldExpr =
    FieldExpr(source(ctx.columnIdentifier))

  override def visitColumnExprLiteral(ctx: ColumnExprLiteralContext): StringLiteral =
    StringLiteral(source(ctx.literal))

  override def visitColumnExprFunction(ctx: ColumnExprFunctionContext): FuncExpr = {
    if (ctx.columnExprList != null) throw new IllegalArgumentException(
      s"unsupported ColumnExprFunction with columnExprList: [${ctx.getClass.getSimpleName}] ${ctx.getText}"
    )
    if (ctx.DISTINCT != null) throw new IllegalArgumentException(
      s"unsupported ColumnExprFunction with DISTINCT: [${ctx.getClass.getSimpleName}] ${ctx.getText}"
    )

    val funcName = ctx.identifier.getText
    val funArgs = ctx.columnArgList.columnArgExpr.asScala.toArray.map { columnArgExprCtx =>
      if (columnArgExprCtx.columnLambdaExpr != null) throw new IllegalArgumentException(
        s"unsupported ColumnLambdaExpr: ${source(columnArgExprCtx)}"
      )
      // recursive visit, but not sure if spark support the nested transform
      visitColumnExpr(columnArgExprCtx.columnExpr)
    }
    FuncExpr(funcName, funArgs)
  }

  ////////////////////////////////////////////////
  ///////////////// visit others /////////////////
  ////////////////////////////////////////////////

  def visitColumnsExpr(ctx: ColumnsExprContext): Expr = ctx match {
    case field: ColumnsExprColumnContext => visitColumnExpr(field.columnExpr)
    case other: ColumnsExprContext => throw new IllegalArgumentException(
        s"unsupported ColumnsExprContext: ${source(other)}"
      )
  }

  override def visitSettingsClause(ctx: SettingsClauseContext): Map[String, String] =
    ctx.settingExprList.settingExpr.asScala.map(se => se.identifier.getText -> source(se.literal)).toMap
}
