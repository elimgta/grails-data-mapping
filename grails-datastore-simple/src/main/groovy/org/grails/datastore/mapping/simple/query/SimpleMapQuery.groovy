/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.mapping.simple.query

import org.springframework.dao.InvalidDataAccessResourceUsageException
import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValue
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.ToOne
import org.grails.datastore.mapping.query.AssociationQuery
import org.grails.datastore.mapping.query.Query
import org.grails.datastore.mapping.query.Restrictions
import org.grails.datastore.mapping.simple.SimpleMapSession
import org.grails.datastore.mapping.simple.engine.SimpleMapEntityPersister
import org.grails.datastore.mapping.model.types.Custom
import org.grails.datastore.mapping.engine.types.CustomTypeMarshaller
import java.util.regex.Pattern
import org.grails.datastore.mapping.query.api.Criteria
import org.grails.datastore.mapping.query.api.QueryableCriteria
import org.springframework.util.Assert
import org.grails.datastore.mapping.query.criteria.FunctionCallingCriterion

/**
 * Simple query implementation that queries a map of objects
 *
 * @author Graeme Rocher
 * @since 1.0
 */
class SimpleMapQuery extends Query {

    Map<String, Map> datastore
    private String family
    private SimpleMapEntityPersister entityPersister

    SimpleMapQuery(SimpleMapSession session, PersistentEntity entity, SimpleMapEntityPersister entityPersister) {
        super(session, entity)
        this.datastore = session.getBackingMap()
        family = getFamily(entity)
        this.entityPersister = entityPersister
    }

    protected List executeQuery(PersistentEntity entity, Query.Junction criteria) {
        def results = []
        def entityMap = [:]
        if (criteria.isEmpty()) {
            populateQueryResult(datastore[family].keySet().toList(), entityMap)
        }
        else {
            def criteriaList = criteria.getCriteria()
            entityMap = executeSubQuery(criteria, criteriaList)
            if (!entity.isRoot()) {
                def childKeys = datastore[family].keySet()
                entityMap = entityMap.subMap(childKeys)

            }
        }

        def nullEntries = entityMap.entrySet().findAll { it.value == null }
        entityMap.keySet().removeAll(nullEntries.collect { it.key })
        if (projections.isEmpty()) {
            results = entityMap.values() as List
        }
        else {
            def projectionList = projections.projectionList
            def projectionCount = projectionList.size()
            def entityList = entityMap.values()

            projectionList.each { Query.Projection p ->

                if (p instanceof Query.IdProjection) {
                    if (projectionCount == 1) {
                        results = entityMap.keySet().toList()
                    }
                    else {
                        results.add(entityMap.keySet().toList())
                    }
                }
                else if (p instanceof Query.CountProjection) {
                    results.add(entityList.size())
                }
                else if(p instanceof Query.CountDistinctProjection) {
                    final uniqueList = new ArrayList(entityList).unique { it."$p.propertyName"}
                    results.add(uniqueList.size() )
                }
                else if (p instanceof Query.PropertyProjection) {
                    def propertyValues = entityList.collect { it."$p.propertyName"}
                    if (p instanceof Query.MaxProjection) {
                        results.add(propertyValues.max())
                    }
                    else if (p instanceof Query.MinProjection) {
                        results.add(propertyValues.min())
                    }
                    else if (p instanceof Query.SumProjection) {
                        results.add(propertyValues.sum())
                    }
                    else if (p instanceof Query.AvgProjection) {
                        def average = propertyValues.sum() / propertyValues.size()
                        results.add(average)
                    }
                    else {

                        PersistentProperty prop = entity.getPropertyByName(p.propertyName)
                        boolean distinct = p instanceof Query.DistinctPropertyProjection
                        if(distinct) {
                            propertyValues = propertyValues.unique()
                        }

                        if (prop) {
                            if (prop instanceof ToOne) {
                                propertyValues = propertyValues.collect {
                                    if (prop.associatedEntity.isInstance(it)) {
                                        return it
                                    }
                                    session.retrieve(prop.type, it)
                                }
                            }
                            if (projectionCount == 1) {
                                results.addAll(propertyValues)
                            }
                            else {
                                results.add(propertyValues)
                            }
                        }
                    }
                }
            }
        }
        if (results) {
            final def total = results.size()
            if (offset > total) return Collections.emptyList()

            // 0..3
            // 0..-1
            // 1..1
            def max = this.max // 20
            def from = offset // 10
            def to = max == -1 ? -1 : (offset + max)-1      // 15
            if (to >= total) to = -1

            def finalResult = results[from..to]
            if (orderBy) {
                orderBy.each { Query.Order order ->
                    def sorted = finalResult.sort { it."${order.property}"}
                    final def os = order.direction.toString()
                    finalResult = os == "DESC" ? sorted.reverse() : sorted
                }
            }
            return finalResult
        }
        return Collections.emptyList()
    }

    def associationQueryHandlers = [
        (AssociationQuery): { allEntities, Association association, AssociationQuery aq->
            Query.Junction queryCriteria = aq.criteria
            return executeAssociationSubQuery(datastore[getFamily(association.associatedEntity)], association.associatedEntity,queryCriteria, aq.association)
        },

        (FunctionCallingCriterion): { allEntities, Association association, FunctionCallingCriterion fcc -> 
            def criterion = fcc.propertyCriterion
            def handler = associationQueryHandlers[criterion.class]
            def function = functionHandlers[fcc.functionName]
            if(handler != null && function != null) {
                try {
                   return handler.call(allEntities, association,criterion, function)
                }
                catch(MissingMethodException e) {
                    throw new InvalidDataAccessResourceUsageException("Unsupported function '$function' used in query")                    
                }
            }
            else {
                throw new InvalidDataAccessResourceUsageException("Unsupported function '$function' used in query")
            }
        },    
        (Query.Like): { allEntities, Association association, Query.Like like, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                def regexFormat = like.pattern.replaceAll('%', '.*?')
                function(it[like.property]) ==~ regexFormat
            }
        },
        (Query.RLike): { allEntities, Association association, Query.RLike like, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                def regexFormat = like.pattern
                function(it[like.property]) ==~ regexFormat
            }
        },
        (Query.ILike): { allEntities, Association association, Query.Like like, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                def regexFormat = like.pattern.replaceAll('%', '.*?')
                def pattern = Pattern.compile(regexFormat, Pattern.CASE_INSENSITIVE)
                pattern.matcher(function(it[like.property])).find()
            }
        },
        (Query.Equals): { allEntities, Association association, Query.Equals eq, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                final value = subqueryIfNecessary(eq)
                function(it[eq.property]) == value
            }
        },
        (Query.IsNull): { allEntities, Association association, Query.IsNull eq, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                function(it[eq.property]) == null
            }
        },
        (Query.NotEquals): { allEntities, Association association, Query.NotEquals eq , Closure function = {it}->
            queryAssociation(allEntities, association) {
                final value = subqueryIfNecessary(eq)
                function(it[eq.property]) != value
            }
        },
        (Query.IsNotNull): { allEntities, Association association, Query.IsNotNull eq , Closure function = {it}->
            queryAssociation(allEntities, association) {
                function(it[eq.property]) != null
            }
        },
        (Query.IdEquals): { allEntities, Association association, Query.IdEquals eq , Closure function = {it}->
            queryAssociation(allEntities, association) {
                function(it[eq.property]) == eq.value
            }
        },
        (Query.Between): { allEntities, Association association, Query.Between between, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                def from = between.from
                def to = between.to
                function(it[between.property])>= from && function(it[between.property]) <= to
            }
        },
        (Query.GreaterThan):{ allEntities, Association association, Query.GreaterThan gt, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                final value = subqueryIfNecessary(gt)
                function(it[gt.property]) > value
            }
        },
        (Query.LessThan):{ allEntities, Association association, Query.LessThan lt, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                final value = subqueryIfNecessary(lt)
                function(it[lt.property]) < value
            }
        },
        (Query.GreaterThanEquals):{ allEntities, Association association, Query.GreaterThanEquals gt, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                final value = subqueryIfNecessary(gt)
                function(it[gt.property]) >= value
            }
        },
        (Query.LessThanEquals):{ allEntities, Association association, Query.LessThanEquals lt, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                final value = subqueryIfNecessary(lt)
                function(it[lt.property]) <= value
            }
        },
        (Query.In):{ allEntities, Association association, Query.In inList, Closure function = {it} ->
            queryAssociation(allEntities, association) {
                inList.values?.contains function(it[inList.property])
            }
        }
    ]

    protected queryAssociation(allEntities, Association association, Closure callable) {
        allEntities.findAll {
            def propertyName = association.name
            if (association instanceof ToOne) {

                def id = it.value[propertyName]

                def associated = session.retrieve(association.associatedEntity.javaClass, id)
                if (associated) {
                    callable.call(associated)
                }
            }
            else {
                def indexer = entityPersister.getAssociationIndexer(it.value, association)
                def results = indexer.query(it.key)
                if (results) {
                    def associatedEntities = session.retrieveAll(association.associatedEntity.javaClass, results)
                    return associatedEntities.any(callable)
                }
            }
        }.keySet().toList()
    }

    protected queryAssociationList(allEntities, Association association, Closure callable) {
        allEntities.findAll {
            def indexer = entityPersister.getAssociationIndexer(it.value, association)
            def results = indexer.query(it.key)
            callable.call(results)
        }.keySet().toList()
    }

    def executeAssociationSubQuery(allEntities, PersistentEntity associatedEntity, Query.Junction queryCriteria, PersistentProperty property) {
        List resultList = []
        for (Query.Criterion criterion in queryCriteria.getCriteria()) {
            def handler = associationQueryHandlers[criterion.getClass()]
            
            if (handler) {
                resultList << handler.call(allEntities, property, criterion)
            }
            else if (criterion instanceof Query.Junction) {
                Query.Junction junction = criterion
                resultList << executeAssociationSubQuery(allEntities,associatedEntity, junction, property)
            }
        }
        return applyJunctionToResults(queryCriteria, resultList)
    }

    def functionHandlers = [
            second: { it[Calendar.SECOND] },
            minute: { it[Calendar.MINUTE] },
            hour: { it[Calendar.HOUR_OF_DAY] },
            year: { it[Calendar.YEAR] },
            month: { it[Calendar.MONTH] },
            day: { it[Calendar.DAY_OF_MONTH] },
            lower: { it.toString().toLowerCase() },
            upper: { it.toString().toUpperCase() },
            trim: { it.toString().trim() },
            length: { it.toString().size() }
    ]
    def handlers = [
        (FunctionCallingCriterion): { FunctionCallingCriterion fcc, PersistentProperty property ->
            def criterion = fcc.propertyCriterion
            def handler = handlers[criterion.class]
            def function = functionHandlers[fcc.functionName]
            if(handler != null && function != null) {
                try {
                    handler.call(criterion, property, function, fcc.onValue)
                }
                catch(MissingMethodException e) {
                    throw new InvalidDataAccessResourceUsageException("Unsupported function '$function' used in query")                    
                }
            }
            else {
                throw new InvalidDataAccessResourceUsageException("Unsupported function '$function' used in query")
            }
        },
        (AssociationQuery): { AssociationQuery aq, PersistentProperty property ->
            Query.Junction queryCriteria = aq.criteria
            return executeAssociationSubQuery(datastore[family], aq.association.associatedEntity, queryCriteria, property)
        },
        (Query.EqualsAll):{ Query.EqualsAll equalsAll, PersistentProperty property, Closure function=null, boolean onValue = false ->
            def name = equalsAll.property
            final values = subqueryIfNecessary(equalsAll, false)
            Assert.isTrue(values.every { property.type.isInstance(it) }, "Subquery returned values that are not compatible with the type of property '$name': $values")
            def allEntities = datastore[family]
            allEntities.findAll { entry ->
                values.every { (function != null ? function(entry.value[name]) : function(entry.value[name])) == it  }
            }
            .collect { it.key }
        },
        (Query.NotEqualsAll):{ Query.NotEqualsAll notEqualsAll, PersistentProperty property, Closure function=null, boolean onValue = false ->
            def name = notEqualsAll.property
            final values = subqueryIfNecessary(notEqualsAll, false)
            Assert.isTrue(values.every { property.type.isInstance(it) }, "Subquery returned values that are not compatible with the type of property '$name': $values")
            def allEntities = datastore[family]
            allEntities.findAll { entry ->
                values.every { (function != null ? function(entry.value[name]) : function(entry.value[name])) != it  }
            }
            .collect { it.key }
        },
        (Query.GreaterThanAll):{ Query.GreaterThanAll greaterThanAll, PersistentProperty property, Closure function=null, boolean onValue = false ->
            def name = greaterThanAll.property
            final values = subqueryIfNecessary(greaterThanAll, false)
            Assert.isTrue(values.every { property.type.isInstance(it) }, "Subquery returned values that are not compatible with the type of property '$name': $values")
            def allEntities = datastore[family]
            allEntities.findAll { entry ->
                values.every { (function != null ? function(entry.value[name]) : entry.value[name]) > it  }
            }
            .collect { it.key }
        },
        (Query.LessThanAll):{ Query.LessThanAll lessThanAll, PersistentProperty property, Closure function=null, boolean onValue = false ->
            def name = lessThanAll.property
            final values = subqueryIfNecessary(lessThanAll, false)
            Assert.isTrue(values.every { property.type.isInstance(it) }, "Subquery returned values that are not compatible with the type of property '$name': $values")
            def allEntities = datastore[family]
            allEntities.findAll { entry ->
                values.every { (function != null ? function(entry.value[name]) : entry.value[name]) < it  }
            }
            .collect { it.key }
        },
        (Query.LessThanEqualsAll):{ Query.LessThanEqualsAll lessThanEqualsAll, PersistentProperty property, Closure function=null, boolean onValue = false ->
            def name = lessThanEqualsAll.property
            final values = subqueryIfNecessary(lessThanEqualsAll, false)
            Assert.isTrue(values.every { property.type.isInstance(it) }, "Subquery returned values that are not compatible with the type of property '$name': $values")
            def allEntities = datastore[family]
            allEntities.findAll { entry ->
                values.every { (function != null ? function(entry.value[name]) : entry.value[name]) <= it  }
            }
            .collect { it.key }
        },
        (Query.GreaterThanEqualsAll):{ Query.GreaterThanEqualsAll greaterThanAll, PersistentProperty property, Closure function=null, boolean onValue = false ->
            def name = greaterThanAll.property
            final values = subqueryIfNecessary(greaterThanAll, false)
            Assert.isTrue(values.every { property.type.isInstance(it) }, "Subquery returned values that are not compatible with the type of property '$name': $values")
            def allEntities = datastore[family]
            allEntities.findAll { entry ->
                values.every { (function != null ? function(entry.value[name]) : entry.value[name]) >= it  }
            }
            .collect { it.key }
        },
        (Query.Equals): { Query.Equals equals, PersistentProperty property, Closure function = null, boolean onValue = false ->
            def indexer = entityPersister.getPropertyIndexer(property)
            final value = subqueryIfNecessary(equals)

            if(function != null) {
                def allEntities = datastore[family]
                allEntities.findAll { function(it.value[property.name]) == value }.collect { it.key }
            }
            else {
                return indexer.query(value)
            }
        },
        (Query.IsNull): { Query.IsNull equals, PersistentProperty property, Closure function = null , boolean onValue = false->

            handlers[Query.Equals].call(new Query.Equals(equals.property, null), property, function)
        },
        (Query.IdEquals): { Query.IdEquals equals, PersistentProperty property ->
            def indexer = entityPersister.getPropertyIndexer(property)
            return indexer.query(equals.value)
        },
        (Query.NotEquals): { Query.NotEquals equals, PersistentProperty property, Closure function = null, boolean onValue = false ->
            def indexed = handlers[Query.Equals].call(new Query.Equals(equals.property, equals.value), property, function)
            return negateResults(indexed)
        },
        (Query.IsNotNull): { Query.IsNotNull equals, PersistentProperty property, Closure function = null, boolean onValue = false ->
            def indexed = handlers[Query.Equals].call(new Query.Equals(equals.property, null), property, function)
            return negateResults(indexed)
        },
        (Query.Like): { Query.Like like, PersistentProperty property ->
            def indexer = entityPersister.getPropertyIndexer(property)

            def root = indexer.indexRoot
            def regexFormat = like.pattern.replaceAll('%', '.*?')
            def pattern = "${root}:${regexFormat}"
            def matchingIndices = entityPersister.indices.findAll { key, value ->
                key ==~ pattern
            }

            Set result = []
            for (indexed in matchingIndices) {
                result.addAll(indexed.value)
            }

            return result.toList()
        },
        (Query.ILike): { Query.ILike like, PersistentProperty property ->
            def regexFormat = like.pattern.replaceAll('%', '.*?')
            return executeLikeWithRegex(entityPersister, property, regexFormat)
        },
        (Query.RLike): { Query.RLike like, PersistentProperty property ->
            def regexFormat = like.pattern
            return executeLikeWithRegex(entityPersister, property, regexFormat)
        },
        (Query.In): { Query.In inList, PersistentProperty property ->
            def disjunction = new Query.Disjunction()
            for (value in inList.values) {
                disjunction.add(Restrictions.eq(inList.name, value))
            }

            executeSubQueryInternal(disjunction, disjunction.criteria)
        },
        (Query.Between): { Query.Between between, PersistentProperty property, Closure function = null, boolean onValue = false ->
            def from = between.from
            def to = between.to
            def name = between.property
            def allEntities = datastore[family]

            if(function != null)
                allEntities.findAll { function(it.value[name]) >= from && function(it.value[name]) <= to }.collect { it.key }
            else
                allEntities.findAll { it.value[name] >= from && it.value[name] <= to }.collect { it.key }
        },
        (Query.GreaterThan): { Query.GreaterThan gt, PersistentProperty property, Closure function = null, boolean onValue = false ->
            def name = gt.property
            final value = subqueryIfNecessary(gt)
            def allEntities = datastore[family]

            allEntities.findAll { (function != null ? function(it.value[name]) : it.value[name]) > value }.collect { it.key }
        },
        (Query.GreaterThanProperty): { Query.GreaterThanProperty gt, PersistentProperty property, Closure function = null, boolean onValue = false ->
            def name = gt.property
            def other = gt.otherProperty
            def allEntities = datastore[family]

            allEntities.findAll { (function != null ? function(it.value[name]) : it.value[name]) > it.value[other] }.collect { it.key }
        },
        (Query.GreaterThanEqualsProperty): { Query.GreaterThanEqualsProperty gt, PersistentProperty property, Closure function = null, boolean onValue = false ->
            def name = gt.property
            def other = gt.otherProperty
            def allEntities = datastore[family]

            allEntities.findAll { it.value[name] >= it.value[other] }.collect { it.key }
        },
        (Query.LessThanProperty): { Query.LessThanProperty gt, PersistentProperty property ->
            def name = gt.property
            def other = gt.otherProperty
            def allEntities = datastore[family]

            allEntities.findAll { it.value[name] < it.value[other] }.collect { it.key }
        },
        (Query.LessThanEqualsProperty): { Query.LessThanEqualsProperty gt, PersistentProperty property ->
            def name = gt.property
            def other = gt.otherProperty
            def allEntities = datastore[family]

            allEntities.findAll { it.value[name] <= it.value[other] }.collect { it.key }
        },
        (Query.EqualsProperty): { Query.EqualsProperty gt, PersistentProperty property ->
            def name = gt.property
            def other = gt.otherProperty
            def allEntities = datastore[family]

            allEntities.findAll { it.value[name] == it.value[other] }.collect { it.key }
        },
        (Query.NotEqualsProperty): { Query.NotEqualsProperty gt, PersistentProperty property ->
            def name = gt.property
            def other = gt.otherProperty
            def allEntities = datastore[family]

            allEntities.findAll { it.value[name] != it.value[other] }.collect { it.key }
        },
        (Query.SizeEquals): { Query.SizeEquals se, PersistentProperty property ->
            def allEntities = datastore[family]
            final value = subqueryIfNecessary(se)
            queryAssociationList(allEntities, property) { it.size() == value }
        },
       (Query.SizeNotEquals): { Query.SizeNotEquals se, PersistentProperty property ->
            def allEntities = datastore[family]
            final value = subqueryIfNecessary(se)
            queryAssociationList(allEntities, property) { it.size() != value }
        },
        (Query.SizeGreaterThan): { Query.SizeGreaterThan se, PersistentProperty property ->
            def allEntities = datastore[family]
            final value = subqueryIfNecessary(se)
            queryAssociationList(allEntities, property) { it.size() > value }
        },
        (Query.SizeGreaterThanEquals): { Query.SizeGreaterThanEquals se, PersistentProperty property ->
            def allEntities = datastore[family]
            final value = subqueryIfNecessary(se)
            queryAssociationList(allEntities, property) { it.size() >= value }
        },
        (Query.SizeLessThan): { Query.SizeLessThan se, PersistentProperty property ->
            def allEntities = datastore[family]
            final value = subqueryIfNecessary(se)
            queryAssociationList(allEntities, property) { it.size() < value }
        },
        (Query.SizeLessThanEquals): { Query.SizeLessThanEquals se, PersistentProperty property ->
            def allEntities = datastore[family]
            final value = subqueryIfNecessary(se)
            queryAssociationList(allEntities, property) { it.size() <= value }
        },
        (Query.GreaterThanEquals): { Query.GreaterThanEquals gt, PersistentProperty property ->
            def name = gt.property
            final value = subqueryIfNecessary(gt)
            def allEntities = datastore[family]

            allEntities.findAll { it.value[name] >= value }.collect { it.key }
        },
        (Query.LessThan): { Query.LessThan lt, PersistentProperty property ->
            def name = lt.property
            final value = subqueryIfNecessary(lt)
            def allEntities = datastore[family]

            allEntities.findAll { it.value[name] < value }.collect { it.key }
        },
        (Query.LessThanEquals): { Query.LessThanEquals lte, PersistentProperty property ->
            def name = lte.property
            final value = subqueryIfNecessary(lte)
            def allEntities = datastore[family]

            allEntities.findAll { it.value[name] <= value }.collect { it.key }
        }
    ]

    protected def subqueryIfNecessary(Query.PropertyCriterion pc, boolean uniqueResult = true) {
        def value = pc.value
        if(value instanceof QueryableCriteria) {
            QueryableCriteria criteria = value
            if(uniqueResult) {
                value = criteria.find()
            }
            else {
                value = criteria.list()
            }
        }

        return value
    }

    protected List executeLikeWithRegex(SimpleMapEntityPersister entityPersister, PersistentProperty property, regexFormat) {
        def indexer = entityPersister.getPropertyIndexer(property)

        def root = indexer.indexRoot
        def pattern = Pattern.compile("${root}:${regexFormat}", Pattern.CASE_INSENSITIVE)
        def matchingIndices = entityPersister.indices.findAll { key, value ->
            pattern.matcher(key).matches()
        }

        Set result = []
        for (indexed in matchingIndices) {
            result.addAll(indexed.value)
        }

        return result.toList()
    }

    private ArrayList negateResults(List results) {
        def entityMap = datastore[family]
        def allIds = new ArrayList(entityMap.keySet())
        allIds.removeAll(results)
        return allIds
    }

    Map executeSubQuery(criteria, criteriaList) {

        def finalIdentifiers = executeSubQueryInternal(criteria, criteriaList)

        Map queryResult = [:]
        populateQueryResult(finalIdentifiers, queryResult)
        return queryResult
    }

    Collection executeSubQueryInternal(criteria, criteriaList) {
        SimpleMapResultList resultList = new SimpleMapResultList(this)
        for (Query.Criterion criterion in criteriaList) {
            if (criterion instanceof Query.Junction) {
                resultList.results << executeSubQueryInternal(criterion, criterion.criteria)
            }
            else {
                PersistentProperty property = getValidProperty(criterion)

                if((property instanceof Custom) && (criterion instanceof Query.PropertyCriterion)) {
                    CustomTypeMarshaller customTypeMarshaller = ((Custom) property).getCustomTypeMarshaller();
                    customTypeMarshaller.query(property, criterion, resultList);
                    continue
                }
                else {
                    def handler = handlers[criterion.getClass()]

                    def results = handler?.call(criterion, property) ?: []
                    resultList.results << results
                }

            }
        }
        return applyJunctionToResults(criteria,resultList.results)
    }

    private List applyJunctionToResults(Query.Junction criteria, List resultList) {
        def finalIdentifiers = []
        if (!resultList.isEmpty()) {
            if (resultList.size() > 1) {
                if (criteria instanceof Query.Conjunction) {
                    def total = resultList.size()
                    finalIdentifiers = resultList[0]
                    for (num in 1..<total) {
                        def secondList = resultList[num]
                        finalIdentifiers = finalIdentifiers.intersect(secondList)
                    }
                }
                else if (criteria instanceof Query.Negation) {
                    def total = resultList.size()
                    finalIdentifiers = negateResults(resultList[0])
                    for (num in 1..<total) {
                        def secondList = negateResults(resultList[num])
                        finalIdentifiers = finalIdentifiers.intersect(secondList)
                    }
                }
                else {
                    finalIdentifiers = resultList.flatten()
                }
            }
            else {
                if (criteria instanceof Query.Negation) {
                    finalIdentifiers = negateResults(resultList[0])
                }
                else {
                    finalIdentifiers = resultList[0]
                }
            }
        }
        return finalIdentifiers
    }

    protected PersistentProperty getValidProperty(criterion) {
        if (criterion instanceof Query.PropertyNameCriterion) {
            def property = entity.getPropertyByName(criterion.property)
            if (property == null) {
                def identity = entity.identity
                if (identity.name == criterion.property) return identity
                else {
                    throw new InvalidDataAccessResourceUsageException("Cannot query [" + entity + "] on non-existent property: " + criterion.property)
                }
            }
            return property
        }
        else if (criterion instanceof AssociationQuery) {
            return criterion.association
        }
    }

    private boolean isIndexed(PersistentProperty property) {
        KeyValue kv = (KeyValue) property.getMapping().getMappedForm()
        return kv.isIndex()
    }

    protected populateQueryResult(identifiers, Map queryResult) {
        for (id in identifiers) {
            queryResult.put(id, session.retrieve(entity.javaClass, id))
        }
    }

    protected String getFamily(PersistentEntity entity) {
        def cm = entity.getMapping()
        String table = null
        if (cm.getMappedForm() != null) {
            table = cm.getMappedForm().getFamily()
        }
        if (table == null) table = entity.getJavaClass().getName()
        return table
    }
}
