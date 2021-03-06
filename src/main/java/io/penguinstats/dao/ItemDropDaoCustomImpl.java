package io.penguinstats.dao;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregationOptions;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.aggregation.LiteralOperators;
import org.springframework.data.mongodb.core.query.Criteria;

import com.mongodb.BasicDBObject;

import io.penguinstats.enums.Server;
import io.penguinstats.model.ItemDrop;
import io.penguinstats.model.QueryConditions;
import io.penguinstats.model.QueryConditions.StageWithTimeRange;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class ItemDropDaoCustomImpl implements ItemDropDaoCustom {

	@Autowired
	MongoTemplate mongoTemplate;

	/** 
	 * @Title: aggregateItemDrops 
	 * @Description: Use aggregation to get item drop times and quantities under given query conditions
	 * @param conditions
	 * @return List<Document>
	 */
	@Override
	public List<Document> aggregateItemDrops(QueryConditions conditions) {
		Long currentTime = System.currentTimeMillis();

		List<String> userIDs = conditions.getUserIDs();
		List<String> itemIds = conditions.getItemIds();
		List<Server> servers = conditions.getServers();
		List<StageWithTimeRange> stages = conditions.getStages();
		Long interval = conditions.getInterval();

		List<AggregationOperation> operations = new LinkedList<>();

		/* Pipe 1: filter by isReliable, isDeleted, stageId and timestamp
			{
			  $match:{
			    $or:[
			      {
			        stageId:"main_01-07",
			        timestamp:{
			          $gt:1586853840000,
			          $lt:91559229418034
			        }
			      },
			      {
			        stageId:"main_04-04",
			        timestamp:{
			          $gt:1586853840000,
			          $lt:91569229418034
			        }
			      }
			    ],
			    isReliable:true,
			    isDeleted:false
			  }
			}
		 */
		List<Criteria> criteriasInAndInPipe1 = new ArrayList<>();

		criteriasInAndInPipe1.add(Criteria.where("isDeleted").is(false));

		if (userIDs.isEmpty())
			criteriasInAndInPipe1.add(Criteria.where("isReliable").is(true));
		else
			criteriasInAndInPipe1.add(Criteria.where("userID").in(userIDs));

		if (!servers.isEmpty())
			criteriasInAndInPipe1.add(Criteria.where("server").in(servers));

		if (!stages.isEmpty()) {
			if (1 == stages.size() && stages.get(0).getStageId() == null) {
				StageWithTimeRange stage = stages.get(0);
				Long min = stage.getStart() == null ? 0L : stage.getStart();
				Long max = stage.getEnd() == null ? System.currentTimeMillis() : stage.getEnd();
				criteriasInAndInPipe1.add(Criteria.where("timestamp").gte(min).lt(max));
			} else {
				List<Criteria> criteriasInOrInPipe1 = new ArrayList<>();
				stages.forEach(stage -> {
					Long min = stage.getStart() == null ? 0L : stage.getStart();
					Long max = stage.getEnd() == null ? System.currentTimeMillis() : stage.getEnd();
					criteriasInOrInPipe1.add(new Criteria().andOperator(Criteria.where("timestamp").gte(min).lt(max),
							Criteria.where("stageId").is(stage.getStageId())));
				});
				criteriasInAndInPipe1.add(new Criteria().orOperator(criteriasInOrInPipe1.toArray(new Criteria[0])));
			}
		}

		operations.add(Aggregation.match(new Criteria().andOperator(criteriasInAndInPipe1.toArray(new Criteria[0]))));

		/* Pipe 2: project section number.
		 * If no interval is provided, which means we are not calculating segmented results, then project
		 * 0.0 as section number for all docs.
		 * Otherwise, we calculate section number using section = (timestamp - baseTime) / interval
			{
			  $project:{
			    _id:0,
			    stageId:1,
			    drops:1,
			    times:1,
			    section:{
			      $trunc:{
			        $divide:[
			          {
			            $subtract:[
			              '$timestamp',
			              1586853840000 // this is 'baseTime'
			            ]
			          },
			          86400000 // this is 'interval'
			        ]
			      }
			    }
			  }
			}
		 */
		if (interval != null) {
			Long baseTime = null;
			if (stages.isEmpty())
				baseTime = 0L;
			else {
				final Long firstStartTime = stages.get(0).getStart();
				boolean passCheck = true;
				for (int i = 1, size = stages.size(); i < size; i++) {
					StageWithTimeRange stage = stages.get(i);
					if (!stage.getStart().equals(firstStartTime)) {
						log.error("start time must be identical for all stages in the conditions");
						passCheck = false;
						break;
					}
				}
				if (passCheck)
					baseTime = firstStartTime == null ? 0L : firstStartTime;
			}

			operations.add(Aggregation.project("drops", "stageId", "times")
					.and(ArithmeticOperators.Trunc.truncValueOf(ArithmeticOperators.Divide
							.valueOf(ArithmeticOperators.Subtract.valueOf("timestamp").subtract(baseTime))
							.divideBy(interval)))
					.as("section"));
		} else {
			operations.add(Aggregation.project("drops", "stageId", "times")
					.and(LiteralOperators.Literal.asLiteral(0.0d)).as("section"));
		}

		/* Pipe 3: group by section and stageId, sum up 'times' to calculate total times for this stage in one section
			{
			  $group:{
			    _id:{
			      section:"$section",
			      stageId:"$stageId"
			    },
			    times:{
			      $sum:"$times"
			    },
			    drops:{
			      $push:"$drops"
			    }
			  }
			}
		 */
		operations
				.add(Aggregation.group("section", "stageId").push("$$ROOT.drops").as("drops").sum("times").as("times"));

		// Pipe 4 & 5: unwind drops twice
		/*
			{
			  $unwind:{
			    path:"$drops",
			    preserveNullAndEmptyArrays:false
			  }
			},
			{
			  $unwind:{
			    path:"$drops",
			    preserveNullAndEmptyArrays:true
			  }
			}
		 */
		// For the first unwind we can ignore those empty drops arrays (actually they will never be empty)
		operations.add(Aggregation.unwind("drops", false));
		// For the second unwind we must not ignore empty arrays, because there may be no any drops in one stage,
		// but we want to preserve its 'times'
		operations.add(Aggregation.unwind("drops", true));

		// Pipe 6 (Optional): filter on itemId
		if (!itemIds.isEmpty()) {
			List<Criteria> criteriasInOrInPipe6 = new ArrayList<>();
			criteriasInOrInPipe6.add(Criteria.where("drops.itemId").in(itemIds));
			criteriasInOrInPipe6.add(Criteria.where("drops.itemId").is(null));
			operations.add(Aggregation.match(new Criteria().orOperator(criteriasInOrInPipe6.toArray(new Criteria[0]))));
		}

		/* Pipe 7: project and group by itemId, sum up 'quantities' to calculate total quantities
			{
			  $group:{
			    _id:{
			      stageId:"$_id.stageId",
			      section:"$_id.section",
			      times:"$times", // this value is the same for one stage under certain section
			      itemId:"$drops.itemId"
			    },
			    quantity:{
			      $sum:"$drops.quantity"
			    }
			  }
			}
		 */
		operations.add(Aggregation.project("section", "stageId", "times").and("drops.itemId").as("itemId")
				.and("drops.quantity").as("quantity"));
		operations.add(Aggregation.group("section", "stageId", "times", "itemId").sum("quantity").as("quantity"));

		Aggregation aggregation =
				newAggregation(operations).withOptions(newAggregationOptions().allowDiskUse(true).build());

		AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, ItemDrop.class, Document.class);

		log.debug(conditions.toString() + ", time = " + (System.currentTimeMillis() - currentTime) + "ms");

		return results.getMappedResults();
	}

	@Override
	public List<Document> aggregateDropPatterns(QueryConditions conditions) {
		Long currentTime = System.currentTimeMillis();

		List<String> userIDs = conditions.getUserIDs();
		List<Server> servers = conditions.getServers();
		List<StageWithTimeRange> stages = conditions.getStages();

		List<AggregationOperation> operations = new LinkedList<>();

		/* Pipe 1: filter by isReliable, isDeleted, stageId and timestamp
			{
			  $match:{
			    $or:[
			      {
			        stageId:"main_01-07",
			        timestamp:{
			          $gt:1586853840000,
			          $lt:91559229418034
			        }
			      },
			      {
			        stageId:"main_04-04",
			        timestamp:{
			          $gt:1586853840000,
			          $lt:91569229418034
			        }
			      }
			    ],
			    isReliable:true,
			    isDeleted:false
			  }
			}
		 */
		List<Criteria> criteriasInAndInPipe1 = new ArrayList<>();

		criteriasInAndInPipe1.add(Criteria.where("isDeleted").is(false));

		if (userIDs.isEmpty())
			criteriasInAndInPipe1.add(Criteria.where("isReliable").is(true));
		else
			criteriasInAndInPipe1.add(Criteria.where("userID").in(userIDs));

		if (!servers.isEmpty())
			criteriasInAndInPipe1.add(Criteria.where("server").in(servers));

		if (!stages.isEmpty()) {
			if (1 == stages.size() && stages.get(0).getStageId() == null) {
				StageWithTimeRange stage = stages.get(0);
				Long min = stage.getStart() == null ? 0L : stage.getStart();
				Long max = stage.getEnd() == null ? System.currentTimeMillis() : stage.getEnd();
				criteriasInAndInPipe1.add(Criteria.where("timestamp").gte(min).lt(max));
			} else {
				List<Criteria> criteriasInOrInPipe1 = new ArrayList<>();
				stages.forEach(stage -> {
					Long min = stage.getStart() == null ? 0L : stage.getStart();
					Long max = stage.getEnd() == null ? System.currentTimeMillis() : stage.getEnd();
					criteriasInOrInPipe1.add(new Criteria().andOperator(Criteria.where("timestamp").gte(min).lt(max),
							Criteria.where("stageId").is(stage.getStageId())));
				});
				criteriasInAndInPipe1.add(new Criteria().orOperator(criteriasInOrInPipe1.toArray(new Criteria[0])));
			}
		}

		operations.add(Aggregation.match(new Criteria().andOperator(criteriasInAndInPipe1.toArray(new Criteria[0]))));

		/* Pipe 2: group by stageId, sum up 'times' to calculate total times for this stage
		{
		  $group:{
		    _id:{
		      stageId:"$stageId"
		    },
		    times:{
		      $sum:"$times"
		    },
		    drops:{
		      $push:{
			    docId: "$$ROOT._id",
			    pattern: "$drops",
			    quantity: "$$ROOT.times"
		      }
		    }
		  }
		}
		*/
		operations.add(Aggregation.group("stageId").push(
				new BasicDBObject("docId", "$$ROOT._id").append("pattern", "$drops").append("quantity", "$$ROOT.times"))
				.as("drops").sum("times").as("times"));

		// Pipe3: unwind drops
		/*
			{
			  $unwind:{
			    path:"$drops",
			    preserveNullAndEmptyArrays:false
			  }
			}
		 */
		operations.add(Aggregation.unwind("drops", false));

		/* Pipe 4: project unwind results
			{
			  $project:{
			    _id:0,
			    stageId:"$_id",
			    times:1,
			    docId:"$drops.docId",
			    pattern:"$drops.pattern",
			    quantity:"$drops.quantity"
			  }
			}
		*/
		operations.add(Aggregation.project("times").and("_id").as("stageId").and("drops.quantity").as("quantity")
				.and("drops.pattern").as("pattern").and("drops.docId").as("docId"));

		/* Pipe 5: group by pattern, sum up their quantities
			{
			  $group:{
			    _id:{
			      stageId:"$stageId",
			      times:"$times",
			      pattern:"$pattern"
			    },
			    quantity:{
			      $sum:"$quantity"
			    },
			    docId:{
			      $first:"$docId"
			    }
			  }
			}
		 */
		operations.add(Aggregation.group("stageId", "times", "pattern").first("docId").as("docId").sum("quantity")
				.as("quantity"));

		/* Pipe 6: unwind pattern
			{
			  $unwind:{
			    path:"$_id.pattern",
			    preserveNullAndEmptyArrays:true
			  }
			}
		 */
		operations.add(Aggregation.unwind("_id.pattern", true));

		/* Pipe 7: sort by itemId
			$sort:{
			  "_id.pattern.itemId":1
			}
		 */
		operations.add(Aggregation.sort(Direction.ASC, "_id.pattern.itemId"));

		/* Pipe 8: pipe 6~8 is to sort all inner pattern array by itemId ASC
			{
			  $group:{
			    _id:{
			      docId:"$docId",
			      times:"$$ROOT._id.times",
			      stageId:"$$ROOT._id.stageId",
			      quantity:"$$ROOT.quantity"
			    },
			    pattern:{
			      $push:"$$ROOT._id.pattern"
			    }
			  }
			}
		 */
		operations.add(Aggregation.group("docId", "times", "stageId", "quantity").push("pattern").as("pattern"));

		/* Pipe 9: group the sorted results again and sum up their quantities
			{
			  $group:{
			    _id:{
			      pattern:"$pattern",
			      stageId:"$_id.stageId",
			      times:"$_id.times"
			    },
			    quantity:{
			      $sum:"$_id.quantity"
			    }
			  }
			}
		 */
		operations.add(Aggregation.group("pattern", "times", "stageId").sum("quantity").as("quantity"));

		Aggregation aggregation =
				newAggregation(operations).withOptions(newAggregationOptions().allowDiskUse(true).build());

		AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, ItemDrop.class, Document.class);

		log.debug(conditions.toString() + ", time = " + (System.currentTimeMillis() - currentTime) + "ms");

		return results.getMappedResults();
	}

	@Override
	public List<Document> aggregateStageTimes(QueryConditions conditions) {
		List<Server> servers = conditions.getServers();
		Long range = conditions.getRange();

		List<AggregationOperation> operations = new LinkedList<>();

		List<Criteria> criteriasInAndInPipe1 = new ArrayList<>();
		criteriasInAndInPipe1.add(Criteria.where("isReliable").is(true));
		criteriasInAndInPipe1.add(Criteria.where("isDeleted").is(false));
		if (!servers.isEmpty())
			criteriasInAndInPipe1.add(Criteria.where("server").in(servers));
		if (range != null) {
			Long max = System.currentTimeMillis();
			Long min = max - range;
			criteriasInAndInPipe1.add(Criteria.where("timestamp").gte(min).lt(max));
		}
		operations.add(Aggregation.match(new Criteria().andOperator(criteriasInAndInPipe1.toArray(new Criteria[0]))));

		operations.add(Aggregation.group("stageId").sum("times").as("times"));

		Aggregation aggregation =
				newAggregation(operations).withOptions(newAggregationOptions().allowDiskUse(true).build());

		AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, ItemDrop.class, Document.class);
		return results.getMappedResults();
	}

	@Override
	public List<Document> aggregateItemQuantities(QueryConditions conditions) {
		List<Server> servers = conditions.getServers();

		List<AggregationOperation> operations = new LinkedList<>();

		List<Criteria> criteriasInAndInPipe1 = new ArrayList<>();
		criteriasInAndInPipe1.add(Criteria.where("isReliable").is(true));
		criteriasInAndInPipe1.add(Criteria.where("isDeleted").is(false));
		if (!servers.isEmpty())
			criteriasInAndInPipe1.add(Criteria.where("server").in(servers));
		operations.add(Aggregation.match(new Criteria().andOperator(criteriasInAndInPipe1.toArray(new Criteria[0]))));

		operations.add(Aggregation.unwind("drops", false));

		operations.add(Aggregation.project().and("drops.itemId").as("itemId").and("drops.quantity").as("quantity"));
		operations.add(Aggregation.group("itemId").sum("quantity").as("quantity"));

		Aggregation aggregation =
				newAggregation(operations).withOptions(newAggregationOptions().allowDiskUse(true).build());

		AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, ItemDrop.class, Document.class);
		return results.getMappedResults();
	}

}
