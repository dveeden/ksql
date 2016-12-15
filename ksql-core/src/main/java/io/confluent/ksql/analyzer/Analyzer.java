package io.confluent.ksql.analyzer;

import com.google.common.collect.ImmutableList;

import io.confluent.ksql.metastore.KQL_STDOUT;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.metastore.StructuredDataSource;
import io.confluent.ksql.parser.tree.*;
import io.confluent.ksql.planner.DefaultTraversalVisitor;
import io.confluent.ksql.planner.plan.JoinNode;
import io.confluent.ksql.planner.plan.PlanNodeId;
import io.confluent.ksql.planner.plan.StructuredDataSourceNode;
import io.confluent.ksql.util.KSQLException;
import io.confluent.ksql.util.Pair;

import org.apache.kafka.connect.data.Field;

public class Analyzer extends DefaultTraversalVisitor<Node, AnalysisContext> {

  Analysis analysis;
  MetaStore metaStore;

  public Analyzer(Analysis analysis, MetaStore metaStore) {
    this.analysis = analysis;
    this.metaStore = metaStore;
  }

  @Override
  protected Node visitQuerySpecification(QuerySpecification node, AnalysisContext context) {

    process(node.getFrom().get(), new AnalysisContext(null, AnalysisContext.ParentType.FROM));
    process(node.getInto().get(), new AnalysisContext(null, AnalysisContext.ParentType.INTO));

    process(node.getSelect(), new AnalysisContext(null, AnalysisContext.ParentType.SELECT));

    if (node.getWhere().isPresent()) {
      analyzeWhere(node.getWhere().get(), context);
    }

    return null;
  }

  @Override
  protected Node visitJoin(Join node, AnalysisContext context) {
    AliasedRelation left = (AliasedRelation) process(node.getLeft(), context);
    AliasedRelation right = (AliasedRelation) process(node.getRight(), context);

    JoinOn joinOn = (JoinOn) (node.getCriteria().get());
    ComparisonExpression comparisonExpression = (ComparisonExpression) joinOn.getExpression();

    String leftKeyFieldName;
    String rightKeyFieldName;

    if (comparisonExpression.getLeft() instanceof DereferenceExpression) {
      DereferenceExpression
          leftDereferenceExpression =
          (DereferenceExpression) comparisonExpression.getLeft();
      leftKeyFieldName = leftDereferenceExpression.getFieldName();
    } else if (comparisonExpression.getLeft() instanceof QualifiedNameReference) {
      QualifiedNameReference
          leftQualifiedNameReference =
          (QualifiedNameReference) comparisonExpression.getLeft();
      leftKeyFieldName = leftQualifiedNameReference.getName().getSuffix();
    } else {
      throw new KSQLException("Join criteria is not supported.");
    }

    if (comparisonExpression.getRight() instanceof DereferenceExpression) {
      DereferenceExpression
          rightDereferenceExpression =
          (DereferenceExpression) comparisonExpression.getRight();
      rightKeyFieldName = rightDereferenceExpression.getFieldName();
    } else if (comparisonExpression.getRight() instanceof QualifiedNameReference) {
      QualifiedNameReference
          rightQualifiedNameReference =
          (QualifiedNameReference) comparisonExpression.getRight();
      rightKeyFieldName = rightQualifiedNameReference.getName().getSuffix();
    } else {
      throw new KSQLException("Join criteria is not supported.");
    }

    if (comparisonExpression.getType() != ComparisonExpression.Type.EQUAL) {
      throw new KSQLException("Join criteria is not supported.");
    }

    String leftSideName = ((Table) left.getRelation()).getName().getSuffix();
    String leftAlias = left.getAlias();
    String rightSideName = ((Table) right.getRelation()).getName().getSuffix();
    String rightAlias = right.getAlias();

    StructuredDataSource leftDataSource = metaStore.getSource(leftSideName.toUpperCase());
    StructuredDataSource rightDataSource = metaStore.getSource(rightSideName.toUpperCase());

    StructuredDataSourceNode
        leftSourceKafkaTopicNode =
        new StructuredDataSourceNode(new PlanNodeId("KafkaTopic_Left"), leftDataSource.getSchema(),
                                 leftDataSource.getKeyField(), leftDataSource.getKafkaTopic().getTopicName(),
                                 leftAlias.toUpperCase(), leftDataSource.getDataSourceType(),
                                 leftDataSource);
    StructuredDataSourceNode
        rightSourceKafkaTopicNode =
        new StructuredDataSourceNode(new PlanNodeId("KafkaTopic_Right"), rightDataSource.getSchema(),
                                 rightDataSource.getKeyField(), rightDataSource.getKafkaTopic().getTopicName(),
                                 rightAlias.toUpperCase(), rightDataSource.getDataSourceType(),
                                 rightDataSource);

    JoinNode.Type joinType;
    switch (node.getType()) {
      case INNER:
        joinType = JoinNode.Type.INNER;
        break;
      case LEFT:
        joinType = JoinNode.Type.LEFT;
        break;
      case RIGHT:
        joinType = JoinNode.Type.RIGHT;
        break;
      case CROSS:
        joinType = JoinNode.Type.CROSS;
        break;
      case FULL:
        joinType = JoinNode.Type.FULL;
        break;
      default:
        throw new KSQLException("Join type is not supported: " + node.getType().name());
    }

    JoinNode joinNode =
        new JoinNode(new PlanNodeId("Join"), joinType, leftSourceKafkaTopicNode,
                     rightSourceKafkaTopicNode, leftKeyFieldName, rightKeyFieldName, leftAlias,
                     rightAlias);

    analysis.setJoin(joinNode);

    return null;
  }

  @Override
  protected Node visitAliasedRelation(AliasedRelation node, AnalysisContext context) {

    Pair<StructuredDataSource, String>
        fromDataSource =
        new Pair<>(
            metaStore.getSource(((Table) node.getRelation()).getName().getSuffix().toUpperCase()),
            node.getAlias().toUpperCase());
    analysis.getFromDataSources().add(fromDataSource);
    return node;
  }

  @Override
  protected Node visitTable(Table node, AnalysisContext context) {

    StructuredDataSource into;
    if (node.isSTDOut) {
      into = new KQL_STDOUT(KQL_STDOUT.KQL_STDOUT_NAME, null, null, StructuredDataSource.DataSourceType
          .KSTREAM);
//    }
//    else if (context.getParentType() == AnalysisContext.ParentType.INTO) {
//      into =
//          new StructuredDataSourceNode(node.getName().getSuffix(), null, null, StructuredDataSource.DataSourceType.KSTREAM,
//                         null,
//                         node.getName().getSuffix());
    } else {
      throw new KSQLException("INTO clause is not set correctly!");
    }
    analysis.setInto(into);
    return null;
  }

  @Override
  protected Node visitCast(Cast node, AnalysisContext context) {
    return process(node.getExpression(), context);
  }

  @Override
  protected Node visitSelect(Select node, AnalysisContext context) {
    ImmutableList.Builder<Expression> outputExpressionBuilder = ImmutableList.builder();

    for (SelectItem selectItem : node.getSelectItems()) {
      if (selectItem instanceof AllColumns) {
        // expand * and T.*
        AllColumns allColumns = (AllColumns) selectItem;
        if ((this.analysis.getFromDataSources() == null) || (this.analysis.getFromDataSources()
                                                                 .isEmpty())) {
          throw new KSQLException("FROM clause was not resolved!");
        }
        if (analysis.getJoin() != null) {
          JoinNode joinNode = analysis.getJoin();
          for (Field field : joinNode.getLeft().getSchema().fields()) {
            QualifiedNameReference
                qualifiedNameReference =
                new QualifiedNameReference(allColumns.getLocation().get(), QualifiedName
                    .of(joinNode.getLeftAlias() + "." + field.name()));
            analysis.addSelectItem(qualifiedNameReference,
                                   joinNode.getLeftAlias() + "_" + field.name());
          }
          for (Field field : joinNode.getRight().getSchema().fields()) {
            QualifiedNameReference
                qualifiedNameReference =
                new QualifiedNameReference(allColumns.getLocation().get(), QualifiedName
                    .of(joinNode.getRightAlias() + "." + field.name()));
            analysis.addSelectItem(qualifiedNameReference,
                                   joinNode.getRightAlias() + "_" + field.name());
          }
        } else {
          for (Field field : this.analysis.getFromDataSources().get(0).getLeft().getSchema()
              .fields()) {
            QualifiedNameReference
                qualifiedNameReference =
                new QualifiedNameReference(allColumns.getLocation().get(), QualifiedName
                    .of(this.analysis.getFromDataSources().get(0).getRight() + "." + field.name()));
            analysis.addSelectItem(qualifiedNameReference, field.name());
          }
        }

      } else if (selectItem instanceof SingleColumn) {
        SingleColumn column = (SingleColumn) selectItem;
        analysis.addSelectItem(column.getExpression(), column.getAlias().get());
      } else {
        throw new IllegalArgumentException(
            "Unsupported SelectItem type: " + selectItem.getClass().getName());
      }
    }

    return null;
  }

  @Override
  protected Node visitQualifiedNameReference(QualifiedNameReference node, AnalysisContext context) {
    return visitExpression(node, context);
  }

  private StructuredDataSource analyzeFrom(QuerySpecification node, AnalysisContext context) {

    return null;
  }

  private void analyzeWhere(Node node, AnalysisContext context) {
    analysis.setWhereExpression((Expression) node);
  }

  private void analyzeSelect(Select select, AnalysisContext context) {

  }


}
