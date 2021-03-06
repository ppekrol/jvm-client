package net.ravendb.client.document.batches;

import java.util.ArrayList;
import java.util.List;

import net.ravendb.abstractions.basic.CleanCloseable;
import net.ravendb.abstractions.data.GetRequest;
import net.ravendb.abstractions.data.GetResponse;
import net.ravendb.abstractions.data.MoreLikeThisQuery;
import net.ravendb.abstractions.data.MultiLoadResult;
import net.ravendb.abstractions.data.QueryResult;
import net.ravendb.abstractions.json.linq.RavenJArray;
import net.ravendb.abstractions.json.linq.RavenJObject;
import net.ravendb.abstractions.json.linq.RavenJToken;
import net.ravendb.client.document.sessionoperations.MultiLoadOperation;
import net.ravendb.client.shard.ShardStrategy;

public class LazyMoreLikeThisOperation<T> implements ILazyOperation {

  private final MultiLoadOperation multiLoadOperation;
  private final MoreLikeThisQuery query;
  private Class<T> clazz;
  private QueryResult queryResult;
  private Object result;
  private boolean requiresRetry;

  @Override
  public QueryResult getQueryResult() {
    return queryResult;
  }


  public void setQueryResult(QueryResult queryResult) {
    this.queryResult = queryResult;
  }

  @Override
  public Object getResult() {
    return result;
  }

  @Override
  public boolean isRequiresRetry() {
    return requiresRetry;
  }


  @SuppressWarnings("unused")
  public LazyMoreLikeThisOperation(Class<T> clazz, MultiLoadOperation multiLoadOperation, MoreLikeThisQuery query) {
    super();
    this.multiLoadOperation = multiLoadOperation;
    this.query = query;
  }


  @Override
  public GetRequest createRequest() {
    String uri = query.getRequestUri();
    int separator = uri.indexOf('?');
    return new GetRequest(uri.substring(0, separator), uri.substring(separator + 1, uri.length() - separator - 1));
  }

  @SuppressWarnings("hiding")
  @Override
  public void handleResponse(GetResponse response) {
    RavenJToken result = response.getResult();
    MultiLoadResult multiLoadResult = new MultiLoadResult();

    multiLoadResult.setIncludes(result.value(RavenJArray.class, "Includes").values(RavenJObject.class));
    multiLoadResult.setResults(result.value(RavenJArray.class, "Results").values(RavenJObject.class));

    handleResponse(multiLoadResult);
  }

  private void handleResponse(MultiLoadResult multiLoadResult) {
    requiresRetry = multiLoadOperation.setResult(multiLoadResult);
    if (requiresRetry == false) {
      result = multiLoadOperation.complete(clazz);
    }
  }

  @SuppressWarnings("hiding")
  @Override
  public void handleResponses(GetResponse[] responses, ShardStrategy shardStrategy) {
    List<MultiLoadResult> list = new ArrayList<>();
    for (GetResponse response: responses) {
      RavenJToken result = response.getResult();
      MultiLoadResult loadResult = new MultiLoadResult();
      list.add(loadResult);
      loadResult.setResults(result.value(RavenJArray.class, "Includes").values(RavenJObject.class));
      loadResult.setIncludes(result.value(RavenJArray.class, "Results").values(RavenJObject.class));
    }

    int capacity = 0;
    for (MultiLoadResult r: list) {
      capacity = Math.max(capacity, r.getResults().size());
    }

    MultiLoadResult finalResult = new MultiLoadResult();
    finalResult.setIncludes(new ArrayList<RavenJObject>());
    List<RavenJObject> rList = new ArrayList<>();
    for (int i =0 ; i < capacity; i++) {
      rList.add(null);
    }
    finalResult.setResults(rList);

    for (MultiLoadResult multiLoadResult: list) {
      finalResult.getIncludes().addAll(multiLoadResult.getIncludes());
      for (int i = 0; i < multiLoadResult.getResults().size(); i++) {
        if (finalResult.getResults().get(i) == null) {
          finalResult.getResults().set(i, multiLoadResult.getResults().get(i));
        }
      }
    }

    requiresRetry = multiLoadOperation.setResult(finalResult);

    if (!requiresRetry) {
      this.result = multiLoadOperation.complete(clazz);
    }
  }

  @Override
  public CleanCloseable enterContext() {
    return multiLoadOperation.enterMultiLoadContext();
  }

}
