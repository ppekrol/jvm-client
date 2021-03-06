package net.ravendb.client.document.batches;

import net.ravendb.abstractions.basic.CleanCloseable;
import net.ravendb.abstractions.data.GetRequest;
import net.ravendb.abstractions.data.GetResponse;
import net.ravendb.abstractions.data.QueryResult;
import net.ravendb.client.shard.ShardStrategy;

public interface ILazyOperation {
  public GetRequest createRequest();
  public Object getResult();
  public QueryResult getQueryResult();
  public boolean isRequiresRetry();
  public void handleResponse(GetResponse response);
  public void handleResponses(GetResponse[] responses, ShardStrategy shardStrategy);
  public CleanCloseable enterContext();

}
