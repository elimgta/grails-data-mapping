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
package org.springframework.datastore.mapping.mongo.query;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.datastore.document.DocumentStoreConnectionCallback;
import org.springframework.datastore.document.mongodb.MongoTemplate;
import org.springframework.datastore.mapping.model.PersistentEntity;
import org.springframework.datastore.mapping.model.PersistentProperty;
import org.springframework.datastore.mapping.model.types.Association;
import org.springframework.datastore.mapping.model.types.ToOne;
import org.springframework.datastore.mapping.mongo.MongoSession;
import org.springframework.datastore.mapping.mongo.engine.MongoEntityPersister;
import org.springframework.datastore.mapping.query.Query;
import org.springframework.datastore.mapping.query.Restrictions;
import org.springframework.datastore.mapping.query.projections.ManualProjections;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;

/**
 * A {@link org.springframework.datastore.mapping.query.Query} implementation for the Mongo document store
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@SuppressWarnings("rawtypes")
public class MongoQuery extends Query{

    
	private static Map<Class, QueryHandler> queryHandlers = new HashMap<Class, QueryHandler>();
    private static Map<Class, QueryHandler> negatedHandlers = new HashMap<Class, QueryHandler>();

    public static final String MONGO_IN_OPERATOR = "$in";

    public static final String MONGO_OR_OPERATOR = "$or";

    public static final String MONGO_GTE_OPERATOR = "$gte";

    public static final String MONGO_LTE_OPERATOR = "$lte";

    public static final String MONGO_GT_OPERATOR = "$gt";

    public static final String MONGO_LT_OPERATOR = "$lt";

    public static final String MONGO_NE_OPERATOR = "$ne";

    public static final String MONGO_NIN_OPERATOR = "$nin";

    static {
    	queryHandlers.put(IdEquals.class, new QueryHandler<IdEquals>() {

			@Override
			public void handle(PersistentEntity entity, IdEquals criterion,
					DBObject query) {
				query.put(MongoEntityPersister.MONGO_ID_FIELD, criterion.getValue());				
			}
		});
        queryHandlers.put(Equals.class, new QueryHandler<Equals>() {
            public void handle(PersistentEntity entity, Equals criterion, DBObject query) {
                String propertyName = getPropertyName(entity, criterion);
                
                query.put(propertyName, criterion.getValue());
            }
        });
        queryHandlers.put(NotEquals.class, new QueryHandler<NotEquals>() {
            public void handle(PersistentEntity entity, NotEquals criterion, DBObject query) {
                DBObject notEqualQuery = new BasicDBObject();
                notEqualQuery.put(MONGO_NE_OPERATOR, criterion.getValue());

                String propertyName = getPropertyName(entity, criterion);
                query.put(propertyName, notEqualQuery);
            }
        });
        queryHandlers.put(Like.class, new QueryHandler<Like>() {
            public void handle(PersistentEntity entity, Like like, DBObject query) {
                Object value = like.getValue();
                if(value == null) value = "null";
                String expr = value.toString().replace("%", ".*");
                if(!expr.startsWith(".*")) {
                	expr = '^'+expr;
                }
                Pattern regex = Pattern.compile(expr);
                String propertyName = getPropertyName(entity, like);
                query.put(propertyName, regex);
            }
        });
        queryHandlers.put(RLike.class, new QueryHandler<RLike>() {
            public void handle(PersistentEntity entity, RLike like, DBObject query) {
                Object value = like.getValue();
                if (value == null) value = "null";
                final String expr = value.toString();
                Pattern regex = Pattern.compile(expr);
                String propertyName = getPropertyName(entity, like);
                query.put(propertyName, regex);
            }
        });
        queryHandlers.put(In.class, new QueryHandler<In>() {
            public void handle(PersistentEntity entity, In in, DBObject query) {
                DBObject inQuery = new BasicDBObject();
                inQuery.put(MONGO_IN_OPERATOR, in.getValues());
                String propertyName = getPropertyName(entity, in);                
                query.put(propertyName, inQuery);
            }
        });
        queryHandlers.put(Between.class, new QueryHandler<Between>() {
            public void handle(PersistentEntity entity, Between between, DBObject query) {
                DBObject betweenQuery = new BasicDBObject();
                betweenQuery.put(MONGO_GTE_OPERATOR, between.getFrom());
                betweenQuery.put(MONGO_LTE_OPERATOR, between.getTo());
                String propertyName = getPropertyName(entity, between);                
                query.put(propertyName, betweenQuery);
            }
        });
        queryHandlers.put(GreaterThan.class, new QueryHandler<GreaterThan>() {
            public void handle(PersistentEntity entity, GreaterThan criterion, DBObject query) {
                DBObject greaterThanQuery = new BasicDBObject();
                greaterThanQuery.put(MONGO_GT_OPERATOR, criterion.getValue());

                String propertyName = getPropertyName(entity, criterion);
                query.put(propertyName, greaterThanQuery);
            }
        });
        queryHandlers.put(GreaterThanEquals.class, new QueryHandler<GreaterThanEquals>() {
            public void handle(PersistentEntity entity, GreaterThanEquals criterion, DBObject query) {
                DBObject greaterThanQuery = new BasicDBObject();
                greaterThanQuery.put(MONGO_GTE_OPERATOR, criterion.getValue());

                String propertyName = getPropertyName(entity, criterion);
                query.put(propertyName, greaterThanQuery);
            }
        });
        queryHandlers.put(LessThan.class, new QueryHandler<LessThan>() {
            public void handle(PersistentEntity entity, LessThan criterion, DBObject query) {
                DBObject lessThanQuery = new BasicDBObject();
                lessThanQuery.put(MONGO_LT_OPERATOR, criterion.getValue());

                String propertyName = getPropertyName(entity, criterion);
                query.put(propertyName, lessThanQuery);
            }
        });
        queryHandlers.put(LessThanEquals.class, new QueryHandler<LessThanEquals>() {
            public void handle(PersistentEntity entity, LessThanEquals criterion, DBObject query) {
                DBObject lessThanQuery = new BasicDBObject();
                lessThanQuery.put(MONGO_LTE_OPERATOR, criterion.getValue());

                String propertyName = getPropertyName(entity, criterion);
                query.put(propertyName, lessThanQuery);
            }
        });


        queryHandlers.put(Conjunction.class, new QueryHandler<Conjunction>() {

            public void handle(PersistentEntity entity, Conjunction criterion, DBObject query) {
                populateMongoQuery(entity, query, criterion);
            }
        });


        queryHandlers.put(Negation.class, new QueryHandler<Negation>() {

            @SuppressWarnings("unchecked")
			public void handle(PersistentEntity entity, Negation criteria, DBObject query) {
                for (Criterion criterion : criteria.getCriteria()) {
                    final QueryHandler queryHandler = negatedHandlers.get(criterion.getClass());
                    if(queryHandler != null) {
                        queryHandler.handle(entity, criterion, query);
                    }
                    else {
                        throw new UnsupportedOperationException("Query of type "+criterion.getClass().getSimpleName()+" cannot be negated");
                    }
                }
            }
        });

        queryHandlers.put(Disjunction.class, new QueryHandler<Disjunction>() {

            @SuppressWarnings("unchecked")
			public void handle(PersistentEntity entity, Disjunction criterion, DBObject query) {
                List orList = new ArrayList();
                for (Criterion subCriterion : criterion.getCriteria()) {
                    final QueryHandler queryHandler = queryHandlers.get(subCriterion.getClass());
                    if(queryHandler != null) {
                        DBObject dbo = new BasicDBObject();
                        queryHandler.handle(entity, subCriterion, dbo);
                        orList.add(dbo);
                    }
                }
                query.put(MONGO_OR_OPERATOR, orList);
            }
        });


        negatedHandlers.put(Equals.class, new QueryHandler<Equals>() {
            @SuppressWarnings("unchecked")
			public void handle(PersistentEntity entity, Equals criterion, DBObject query) {
                queryHandlers.get(NotEquals.class).handle(entity, Restrictions.ne(criterion.getProperty(), criterion.getValue()), query);
            }
        });
        negatedHandlers.put(NotEquals.class, new QueryHandler<NotEquals>() {
            @SuppressWarnings("unchecked")
			public void handle(PersistentEntity entity, NotEquals criterion, DBObject query) {
                queryHandlers.get(Equals.class).handle(entity, Restrictions.eq(criterion.getProperty(), criterion.getValue()), query);
            }
        });
       negatedHandlers.put(In.class, new QueryHandler<In>() {
           public void handle(PersistentEntity entity, In in, DBObject query) {
               DBObject inQuery = new BasicDBObject();
               inQuery.put(MONGO_NIN_OPERATOR, in.getValues());
               query.put(in.getProperty(), inQuery);
           }
       });
        negatedHandlers.put(Between.class, new QueryHandler<Between>() {
            public void handle(PersistentEntity entity, Between between, DBObject query) {
                DBObject betweenQuery = new BasicDBObject();
                betweenQuery.put(MONGO_LTE_OPERATOR, between.getFrom());
                betweenQuery.put(MONGO_GTE_OPERATOR, between.getTo());
                query.put(between.getProperty(), betweenQuery);
            }
        });
        negatedHandlers.put(GreaterThan.class, new QueryHandler<GreaterThan>() {
            @SuppressWarnings("unchecked")
			public void handle(PersistentEntity entity, GreaterThan criterion, DBObject query) {
                queryHandlers.get(LessThan.class).handle(entity, Restrictions.lt(criterion.getProperty(), criterion.getValue()), query);
            }
        });
        negatedHandlers.put(GreaterThanEquals.class, new QueryHandler<GreaterThanEquals>() {
            @SuppressWarnings("unchecked")
			public void handle(PersistentEntity entity, GreaterThanEquals criterion, DBObject query) {
                queryHandlers.get(LessThanEquals.class).handle(entity, Restrictions.lte(criterion.getProperty(), criterion.getValue()), query);
            }
        });
        negatedHandlers.put(LessThan.class, new QueryHandler<LessThan>() {
            @SuppressWarnings("unchecked")
			public void handle(PersistentEntity entity, LessThan criterion, DBObject query) {
                queryHandlers.get(GreaterThan.class).handle(entity, Restrictions.gt(criterion.getProperty(), criterion.getValue()), query);
            }
        });
        negatedHandlers.put(LessThanEquals.class, new QueryHandler<LessThanEquals>() {
            @SuppressWarnings("unchecked")
			public void handle(PersistentEntity entity, LessThanEquals criterion, DBObject query) {
                queryHandlers.get(GreaterThanEquals.class).handle(entity, Restrictions.gte(criterion.getProperty(), criterion.getValue()), query);
            }
        });



    }

    private MongoSession mongoSession;
    private MongoEntityPersister mongoEntityPersister;
    private ManualProjections manualProjections;
    public MongoQuery(MongoSession session, PersistentEntity entity) {
        super(session, entity);
        this.mongoSession = session;
        this.manualProjections = new ManualProjections(entity);
        this.mongoEntityPersister = (MongoEntityPersister) session.getPersister(entity);
    }

    @Override
    protected List executeQuery(final PersistentEntity entity, final Junction criteria) {
        final MongoTemplate template = mongoSession.getMongoTemplate(entity);

        return (List) template.execute(new DocumentStoreConnectionCallback<DB, Object>(){

            @SuppressWarnings("unchecked")
			public Object doInConnection(DB db) throws Exception {

                final DBCollection collection = db.getCollection(mongoEntityPersister.getCollectionName(entity));
                if(uniqueResult) {
                    final DBObject dbObject;
                    if(criteria.isEmpty()) {
                        if(entity.isRoot()) {
                            dbObject = collection.findOne();
                        }
                        else {
                            DBObject query = new BasicDBObject(MongoEntityPersister.MONGO_CLASS_FIELD, entity.getDiscriminator());
                            dbObject = collection.findOne(query);
                        }
                    }
                    else {
                        DBObject query = createQueryObject(entity);
                        populateMongoQuery(entity, query,criteria);

                        dbObject = collection.findOne(query);
                    }
                    final Object object = createObjectFromDBObject(dbObject);
                    return wrapObjectResultInList(object);
                }
                else {
                    DBCursor cursor;
                    DBObject query = createQueryObject(entity);

                    


                    final List<Projection> projectionList = projections().getProjectionList();
                    if(projectionList.isEmpty()) {
                    	cursor = executeQuery(entity, criteria, collection, query);
                        return new MongoResultList(cursor, mongoEntityPersister);
                    }
                    else {
                        List projectedResults = new ArrayList();
                        for (Projection projection : projectionList) {
                            if(projection instanceof CountProjection) {
                            	// TODO: For some reason the below doesn't return the expected result whilst executing the query and returning the cursor does
                                //projectedResults.add(collection.getCount(query));
                            	cursor = executeQuery(entity, criteria, collection, query);
                            	projectedResults.add(cursor.size());
                            }
                            else if(projection instanceof MinProjection) {
                            	cursor = executeQuery(entity, criteria, collection, query);
                                MinProjection mp = (MinProjection) projection;

                                MongoResultList results = new MongoResultList(cursor, mongoEntityPersister);
                                projectedResults.add(manualProjections.min((Collection) results.clone(), mp.getPropertyName()));
                            }
                            else if(projection instanceof MaxProjection) {
                            	cursor = executeQuery(entity, criteria, collection, query);
                                MaxProjection mp = (MaxProjection) projection;

                                MongoResultList results = new MongoResultList(cursor, mongoEntityPersister);
                                projectedResults.add( manualProjections.max((Collection) results.clone(), mp.getPropertyName()) );
                            }
                            else if((projection instanceof PropertyProjection) || (projection instanceof IdProjection)) {
                            	final PersistentProperty persistentProperty;
                            	final String propertyName;
                            	if(projection instanceof IdProjection) {
                            		persistentProperty = entity.getIdentity();
                            		propertyName = MongoEntityPersister.MONGO_ID_FIELD;
                            	}
                            	else {
                                    PropertyProjection pp = (PropertyProjection) projection;
                                    persistentProperty = entity.getPropertyByName(pp.getPropertyName());
                            		propertyName = pp.getPropertyName();
                            	}
                                if(persistentProperty != null) {
                                    List propertyResults = collection.distinct(propertyName, query);

                                    if(persistentProperty instanceof ToOne) {
                                        Association a = (Association) persistentProperty;
                                        propertyResults = session.retrieveAll(a.getAssociatedEntity().getJavaClass(), propertyResults);
                                    }

                                    if(projectedResults.size() == 0 && projectionList.size() == 1) {
                                        return propertyResults;
                                    }
                                    else {
                                        projectedResults.add(propertyResults);
                                    }
                                }
                                else {
                                    throw new InvalidDataAccessResourceUsageException("Cannot use ["+projection.getClass().getSimpleName()+"] projection on non-existent property: " + propertyName);
                                }
                            }
                        }

                        return projectedResults;
                    }

                }
            }

			protected DBCursor executeQuery(final PersistentEntity entity,
					final Junction criteria, final DBCollection collection,
					DBObject query) {
				final DBCursor cursor;
				if(criteria.isEmpty()) {
				    cursor = executeQueryAndApplyPagination(collection, query);
				}
				else {

				    populateMongoQuery(entity, query, criteria);

				    cursor = executeQueryAndApplyPagination(collection,query);

				}
				return cursor;
			}

			protected DBCursor executeQueryAndApplyPagination(
					final DBCollection collection, DBObject query) {
				final DBCursor cursor;
				cursor = collection.find(query);
                if(offset > 0) {
                    cursor.skip(offset);
                }
                if(max > -1) {
                    cursor.limit(max);
                }

                for (Order order : orderBy) {
                    DBObject orderObject = new BasicDBObject();
                    orderObject.put(order.getProperty(), order.getDirection() == Order.Direction.DESC ? -1 : 1);
                    cursor.sort(orderObject);
                }
				
				return cursor;
			}

        });
    }

    private DBObject createQueryObject(PersistentEntity entity) {
        DBObject query;
        if(entity.isRoot()) {
            query = new BasicDBObject();
        }
        else {
            query = new BasicDBObject(MongoEntityPersister.MONGO_CLASS_FIELD, entity.getDiscriminator());
        }
        return query;
    }

    @SuppressWarnings("unchecked")
	public static void populateMongoQuery(PersistentEntity entity, DBObject query, Junction criteria) {

        List disjunction = null;
        if(criteria instanceof Disjunction) {
            disjunction = new ArrayList();
            query.put(MONGO_OR_OPERATOR,disjunction);
        }
        for (Criterion criterion : criteria.getCriteria()) {
            final QueryHandler queryHandler = queryHandlers.get(criterion.getClass());
            if(queryHandler != null) {
                DBObject dbo = query;
                if(disjunction != null) {
                    dbo = new BasicDBObject();
                    disjunction.add(dbo);
                }
                queryHandler.handle(entity, criterion, dbo);
            }
            else {
                throw new UnsupportedOperationException("Queries of type "+criterion.getClass().getSimpleName()+" are not supported by this implementation");
            }
        }
    }

    protected static String getPropertyName(PersistentEntity entity,
			PropertyCriterion criterion) {
		String propertyName = criterion.getProperty();
		if(entity.isIdentityName(propertyName)) {
			propertyName = MongoEntityPersister.MONGO_ID_FIELD;                	
		}
		return propertyName;
	}

	private Object createObjectFromDBObject(DBObject dbObject) {
        final Object id = dbObject.get(MongoEntityPersister.MONGO_ID_FIELD);
        return mongoEntityPersister.createObjectFromNativeEntry(getEntity(), (Serializable) id, dbObject);
    }

    @SuppressWarnings("unchecked")
	private Object wrapObjectResultInList(Object object) {
        List result = new ArrayList();
        result.add(object);
        return result;
    }

    private static interface QueryHandler<T> {
        public void handle(PersistentEntity entity, T criterion, DBObject query);
    }

    public static class MongoResultList extends ArrayList{

        private MongoEntityPersister mongoEntityPersister;

        @SuppressWarnings("unchecked")
		public MongoResultList(DBCursor cursor, MongoEntityPersister mongoEntityPersister) {
        	super.addAll(cursor.toArray());
            this.mongoEntityPersister = mongoEntityPersister;
        }
        
        

        @Override
        public Object get(int index) {
        	Object object = super.get(index);
            
            if(object instanceof DBObject) {
                object = convertDBObject(object);
                
                set(index, object);            		
            }
            return  object;

        }



		protected Object convertDBObject(Object object) {
			final DBObject dbObject = (DBObject) object;
			Object id = dbObject.get(MongoEntityPersister.MONGO_ID_FIELD);
			object = mongoEntityPersister.createObjectFromNativeEntry(mongoEntityPersister.getPersistentEntity(), (Serializable) id, dbObject);
			return object;
		}
        
        @Override
        public Object clone() {
        	List arrayList = new ArrayList();
        	for (Object object : this) {
        		if(object instanceof DBObject) {
        			object = convertDBObject(object);
        		}
				arrayList.add(object);
			}
        	return arrayList;
        }

    }
}