package net.ravendb.client.document;

import com.google.common.io.Closeables;
import net.ravendb.abstractions.basic.*;
import net.ravendb.abstractions.closure.Action0;
import net.ravendb.abstractions.closure.Action1;
import net.ravendb.abstractions.closure.Function1;
import net.ravendb.abstractions.closure.Predicate;
import net.ravendb.abstractions.connection.ErrorResponseException;
import net.ravendb.abstractions.data.*;
import net.ravendb.abstractions.exceptions.OperationCancelledException;
import net.ravendb.abstractions.exceptions.subscriptions.SubscriptionClosedException;
import net.ravendb.abstractions.exceptions.subscriptions.SubscriptionDoesNotExistException;
import net.ravendb.abstractions.exceptions.subscriptions.SubscriptionException;
import net.ravendb.abstractions.exceptions.subscriptions.SubscriptionInUseException;
import net.ravendb.abstractions.json.linq.RavenJObject;
import net.ravendb.abstractions.logging.ILog;
import net.ravendb.abstractions.logging.LogManager;
import net.ravendb.abstractions.util.AutoResetEvent;
import net.ravendb.abstractions.util.ManualResetEvent;
import net.ravendb.client.changes.*;
import net.ravendb.client.connection.IDatabaseCommands;
import net.ravendb.client.connection.RavenJObjectIterator;
import net.ravendb.client.connection.ServerClient;
import net.ravendb.client.connection.implementation.HttpJsonRequest;
import net.ravendb.client.connection.profiling.ConcurrentSet;
import net.ravendb.client.extensions.HttpJsonRequestExtension;
import net.ravendb.client.utils.CancellationTokenSource;
import net.ravendb.client.utils.Observers;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.codehaus.jackson.JsonParser;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class Subscription<T> implements IObservable<T>, CleanCloseable {
  protected static final ILog logger = LogManager.getCurrentClassLogger();

  protected AutoResetEvent newDocuments = new AutoResetEvent(false);
  private ManualResetEvent anySubscriber = new ManualResetEvent(false);

  private final IDatabaseCommands commands;
  private final IDatabaseChanges changes;
  private final DocumentConvention conventions;
  private final Action0 ensureOpenSubscription;
  private final ConcurrentSet<IObserver<T>> subscribers = new ConcurrentSet<>();
  private final SubscriptionConnectionOptions options;
  private final CancellationTokenSource cts = new CancellationTokenSource();
  private GenerateEntityIdOnTheClient generateEntityIdOnTheClient;
  private final boolean isStronglyTyped;
  private boolean completed;
  private final long id;
  private final Class<T> clazz;
  private CleanCloseable dataSubscriptionReleasedObserver;
  private boolean disposed;

  private EventHandler<VoidArgs> eventHandler;

  private List<EventHandler<VoidArgs>> beforeBatch = new ArrayList<>();
  private List<EventHandler<DocumentProcessedEventArgs>> afterBatch = new ArrayList<>();
  private List<EventHandler<VoidArgs>> beforeAcknowledgment = new ArrayList<>();
  private List<EventHandler<LastProcessedEtagEventArgs>> afterAcknowledgment = new ArrayList<>();

  private boolean isErroredBecauseOfSubscriber;
  private Exception lastSubscriberException;
  private Exception subscriptionConnectionException;
  private boolean connectionClosed;
  private boolean firstConnection = true;

  Subscription(Class<T> clazz, long id, final String database, SubscriptionConnectionOptions options,
               final IDatabaseCommands commands, IDatabaseChanges changes, final DocumentConvention conventions,
               final boolean open, Action0 ensureOpenSubscription) {
    this.clazz = clazz;
    this.id = id;
    this.options = options;
    this.commands = commands;
    this.changes = changes;
    this.conventions = conventions;
    this.ensureOpenSubscription = ensureOpenSubscription;

    if (!RavenJObject.class.equals(clazz)) {
      isStronglyTyped = true;
      generateEntityIdOnTheClient = new GenerateEntityIdOnTheClient(conventions, new Function1<Object, String>() {
        @Override
        public String apply(Object entity) {
          return conventions.generateDocumentKey(database, commands, entity);
        }
      });
    } else {
      isStronglyTyped = false;
    }

    if (open) {
      start();
    } else {
      if (options.getStrategy() != SubscriptionOpeningStrategy.WAIT_FOR_FREE) {
        throw new IllegalStateException("Subscription isn't open while its opening strategy is: " + options.getStrategy());
      }
    }

    if (options.getStrategy() == SubscriptionOpeningStrategy.WAIT_FOR_FREE) {
      waitForSubscriptionReleased();
    }

  }

  private void start() {
    startWatchingDocs();
    startPullingTask = startPullingDocs();
  }

  public void addBeforeBatchHandler(EventHandler<VoidArgs> handler) {
    beforeBatch.add(handler);
  }

  public void removeBeforeBatchHandler(EventHandler<VoidArgs> handler) {
    beforeBatch.remove(handler);
  }

  public void addAfterBatchHandler(EventHandler<DocumentProcessedEventArgs> handler) {
    afterBatch.add(handler);
  }

  public void removeAfterBatchHandler(EventHandler<DocumentProcessedEventArgs> handler) {
    afterBatch.remove(handler);
  }

  public void addBeforeAcknowledgmentHandler(EventHandler<VoidArgs> handler) {
    beforeAcknowledgment.add(handler);
  }

  public void removeBeforeAcknowledgmentHandler(EventHandler<VoidArgs> handler) {
    beforeAcknowledgment.remove(handler);
  }

  public void addAfterAcknowledgmentHandler(EventHandler<LastProcessedEtagEventArgs> handler) {
    afterAcknowledgment.add(handler);
  }

  public void removeAfterAcknowledgmentHandler(EventHandler<LastProcessedEtagEventArgs> handler) {
    afterAcknowledgment.remove(handler);
  }

  /**
   * It determines if the subscription is closed.
   */
  public boolean isConnectionClosed() {
    return connectionClosed;
  }

  private Thread startPullingTask;

  private Closeable putDocumentsObserver;
  private Closeable endedBulkInsertsObserver;
  private Etag lastProcessedEtagOnServer = null;

  /**
   * @return It indicates if the subscription is in errored state because one of subscribers threw an exception.
     */
  public boolean isErroredBecauseOfSubscriber() {
    return isErroredBecauseOfSubscriber;
  }

  /**
   *  The last subscription connection exception.
     */
  public Exception getSubscriptionConnectionException() {
    return subscriptionConnectionException;
  }

  /**
   * The last exception thrown by one of subscribers.
     */
  public Exception getLastSubscriberException() {
    return lastSubscriberException;
  }

  @SuppressWarnings({"boxing", "unchecked"})
  private void pullDocuments() throws IOException, InterruptedException {
    try {
      Etag lastProcessedEtagOnClient = null;

      while (true) {
        anySubscriber.waitOne();

        cts.getToken().throwIfCancellationRequested();

        boolean pulledDocs = false;
        lastProcessedEtagOnServer = null;
        int processedDocs = 0;
        try (HttpJsonRequest subscriptionRequest = createPullingRequest()) {
          try (CloseableHttpResponse response = subscriptionRequest.executeRawResponse()) {
            HttpJsonRequestExtension.assertNotFailingResponse(response);

            try (RavenJObjectIterator streamedDocs = ServerClient.yieldStreamResults(response, 0, Integer.MAX_VALUE, null, new Function1<JsonParser, Boolean>() {
              @SuppressWarnings({"synthetic-access"})
              @Override
              public Boolean apply(JsonParser reader) {
                try {
                  if (!"LastProcessedEtag".equals(reader.getText())) {
                    return false;
                  }
                  if (reader.nextToken() == null) {
                    return false;
                  }
                  lastProcessedEtagOnServer = Etag.parse(reader.getText());
                  return true;
                } catch (IOException e) {
                  return false;
                }
              }
            })) {
              while (streamedDocs.hasNext()) {
                if (pulledDocs == false) {
                  EventHelper.invoke(beforeBatch, this, EventArgs.EMPTY);
                }
                pulledDocs = true;

                cts.getToken().throwIfCancellationRequested();

                RavenJObject jsonDoc = streamedDocs.next();
                T instance = null;
                for (IObserver<T> subscriber : subscribers) {
                  try {
                    if (isStronglyTyped) {
                      if (instance == null) {
                        instance = conventions.createSerializer().deserialize(jsonDoc.toString(), clazz);
                        String docId = jsonDoc.get(Constants.METADATA).value(String.class, "@id");
                        if (StringUtils.isNotEmpty(docId)) {
                          generateEntityIdOnTheClient.trySetIdentity(instance, docId);
                        }
                      }
                      subscriber.onNext(instance);
                    } else {
                      subscriber.onNext((T) jsonDoc);
                    }
                  } catch (Exception ex) {
                    logger.warnException("Subscriber threw an exception", ex);
                    if (options.isIgnoreSubscribersErrors() == false) {
                      isErroredBecauseOfSubscriber = true;
                      lastSubscriberException = ex;
                      try {
                        subscriber.onError(ex);
                      } catch (Exception e) {
                        // can happen if a subscriber doesn't have an onError handler - just ignore it
                      }
                      break;
                    }
                  }
                }
                processedDocs++;
                if (isErroredBecauseOfSubscriber) {
                  break;
                }
              }
            }
          }

          if (isErroredBecauseOfSubscriber) {
            break;
          }

          if (lastProcessedEtagOnServer != null) {

            // This is an acknowledge when the server returns documents to the subscriber.
            if (pulledDocs) {
              EventHelper.invoke(beforeAcknowledgment, this, EventArgs.EMPTY);
              acknowledgeBatchToServer(lastProcessedEtagOnServer);
              EventHelper.invoke(afterAcknowledgment, this, new LastProcessedEtagEventArgs(lastProcessedEtagOnServer));

              EventHelper.invoke(afterBatch, this, new DocumentProcessedEventArgs(processedDocs));
              continue; // try to pull more documents from subscription
            } else {
              if (!lastProcessedEtagOnClient.equals(lastProcessedEtagOnServer)) {
                // This is a silent acknowledge, this can happen because there was no documents in range
                // to be accessible in the time available. This condition can happen when documents must match
                // a set of conditions to be eligible.
                acknowledgeBatchToServer(lastProcessedEtagOnServer);

                lastProcessedEtagOnClient = lastProcessedEtagOnServer;

                continue; // try to pull more documents from subscription
              }
            }
          }

          while (newDocuments.waitOne(options.getClientAliveNotificationInterval(), TimeUnit.MILLISECONDS) == false) {
            try (HttpJsonRequest clientAliveRequest = createClientAliveRequest()) {
              clientAliveRequest.executeRequest();
            }
          }
        }
      }
    } catch (ErrorResponseException e) {
      SubscriptionException subscriptionException = DocumentSubscriptions.tryGetSubscriptionException(e);
      if (subscriptionException != null) {
        throw subscriptionException;
      }
      throw e;
    }
  }

  private void acknowledgeBatchToServer(Etag lastProcessedEtagOnServer) {
    try (HttpJsonRequest acknowledgmentRequest = createAcknowledgmentRequest(lastProcessedEtagOnServer)) {
      try {
        acknowledgmentRequest.executeRequest();
      } catch (Exception e) {
        if (acknowledgmentRequest.getResponseStatusCode() != HttpStatus.SC_REQUEST_TIMEOUT) // ignore acknowledgment timeouts
          throw e;
      }
    }
  }

   private Thread startPullingDocs() {
     Runnable runnable = new Runnable() {
      @SuppressWarnings("synthetic-access")
      @Override
      public void run() {
        try {
          subscriptionConnectionException = null;
          pullDocuments();
        } catch (Exception ex) {
          if (cts.getToken().isCancellationRequested()) {
            return;
          }

          if (tryHandleRejectedConnection(ex, false)) {
            return;
          }

          restartPullingTask();
        }

        if (isErroredBecauseOfSubscriber) {
          try {
            startPullingTask = null; // prevent from calling Wait() on this in Dispose because we are already inside this task
            close();
          } catch (Exception e) {
            logger.warnException("Exception happened during an attempt to close subscription after it had become faulted", e);
          }
        }
      }
    };

    Thread pullingThread = new Thread(runnable, "Subscription pulling thread");
    pullingThread.start();
    return pullingThread;
  }

  private void restartPullingTask() {
    boolean connected = false;
    while (!connected) {
      try {
        Thread.sleep(options.getTimeToWaitBeforeConnectionRetry());
        ensureOpenSubscription.apply();
        connected = true;
      } catch (Exception ex) {
        if (tryHandleRejectedConnection(ex, true)) {
          return;
        }
      }
    }
    startPullingTask = startPullingDocs();
  }

  private boolean tryHandleRejectedConnection(Exception ex, boolean handleClosedException) {
    subscriptionConnectionException = ex;
    if (ex instanceof SubscriptionInUseException ||  // another client has connected to the subscription
            ex instanceof  SubscriptionDoesNotExistException ||  // subscription has been deleted meanwhile
            (handleClosedException && ex instanceof SubscriptionClosedException)) { // someone forced us to drop the connection by calling Subscriptions.Release
      connectionClosed = true;
      startPullingTask = null;  // prevent from calling wait() on this in close
      close();
      return true;
    }
    return false;
  }

  private void startWatchingDocs() {
    eventHandler = new EventHandler<VoidArgs>() {
      @SuppressWarnings("synthetic-access")
      @Override
      public void handle(Object sender, VoidArgs event) {
        changesApiConnectionChanged(sender, event);
      }
    };
    changes.addConnectionStatusChanged(eventHandler);

    putDocumentsObserver = changes.forAllDocuments().subscribe(new ObserverAdapter<DocumentChangeNotification>() {
      @Override
      public void onNext(DocumentChangeNotification notification) {
        if (DocumentChangeTypes.PUT.equals(notification.getType()) && !notification.getId().startsWith("Raven/")) {
          newDocuments.set();
        }
      }
    });

    endedBulkInsertsObserver = changes.forBulkInsert().subscribe(new ObserverAdapter<BulkInsertChangeNotification>() {
      @Override
      public void onNext(BulkInsertChangeNotification notification) {
        if (DocumentChangeTypes.BULK_INSERT_ENDED.equals(notification.getType())) {
          newDocuments.set();
        }
      }
    });
  }

  private void waitForSubscriptionReleased() {
    IObservable<DataSubscriptionChangeNotification> dataSubscriptionObservable = changes.forDataSubscription(id);
    dataSubscriptionReleasedObserver = dataSubscriptionObservable.subscribe(new Observers.ActionBasedObserver<>(new Action1<DataSubscriptionChangeNotification>() {
      @Override
      public void apply(DataSubscriptionChangeNotification notification) {
        if (notification.getType() == DataSubscriptionChangeTypes.SUBSCRIPTION_RELEASED) {
          try {
            ensureOpenSubscription.apply();
          } catch (Exception e) {
            return ;
          }

          // succeeded in opening the subscription

          // no longer need to be notified about subscription status changes
          dataSubscriptionReleasedObserver.close();
          dataSubscriptionReleasedObserver = null;

          // start standard stuff
          start();
        }
      }
    }));
  }

  @SuppressWarnings("unused")
  private void changesApiConnectionChanged(Object sender, EventArgs e) {
    RemoteDatabaseChanges changesApi = (RemoteDatabaseChanges) sender;
    if (changesApi.isConnected()){
      newDocuments.set();
    }
  }

  @Override
  public CleanCloseable subscribe(final IObserver<T> observer) {
    if (isErroredBecauseOfSubscriber) {
      throw new IllegalStateException("Subscription encountered errors and stopped. Cannot add any subscriber.");
    }

    if (subscribers.add(observer)) {
      if (subscribers.size() == 1) {
        anySubscriber.set();
      }
    }

    return new CleanCloseable() {
      @SuppressWarnings("synthetic-access")
      @Override
      public void close() {
        subscribers.remove(observer);
        if (subscribers.isEmpty()) {
          anySubscriber.reset();
        }
      }
    };
  }

    @SuppressWarnings("boxing")
    private HttpJsonRequest createAcknowledgmentRequest(Etag lastProcessedEtag) {
      return commands.createRequest(HttpMethods.POST,
        String.format("/subscriptions/acknowledgeBatch?id=%d&lastEtag=%s&connection=%s", id, lastProcessedEtag, options.getConnectionId()));
    }

    @SuppressWarnings("boxing")
    private HttpJsonRequest createPullingRequest() {
      return commands.createRequest(HttpMethods.GET,
        String.format("/subscriptions/pull?id=%d&connection=%s", id, options.getConnectionId()));
    }

    @SuppressWarnings("boxing")
    private HttpJsonRequest createClientAliveRequest() {
      return commands.createRequest(HttpMethods.PATCH,
        String.format("/subscriptions/client-alive?id=%d&connection=%s", id, options.getConnectionId()));
    }

    @SuppressWarnings("boxing")
    private HttpJsonRequest createCloseRequest() {
      return commands.createRequest(HttpMethods.POST,
        String.format("/subscriptions/close?id=%d&connection=%s", id, options.getConnectionId()));
    }

    private void onCompletedNotification() {
      if (completed) {
        return;
      }

      for (IObserver<T> subscriber: subscribers) {
        subscriber.onCompleted();
      }
      completed = true;
    }

    @Override
    public void close() {
      if (disposed) {
        return;
      }
      disposed = true;

      onCompletedNotification();

      subscribers.clear();

      Closeables.closeQuietly(putDocumentsObserver);

      Closeables.closeQuietly(endedBulkInsertsObserver);

      Closeables.closeQuietly(dataSubscriptionReleasedObserver);

      if (changes instanceof CleanCloseable) {
        Closeable closeableChanges = (Closeable) changes;
        Closeables.closeQuietly(closeableChanges);
      }

      cts.cancel();

      newDocuments.set();
      anySubscriber.set();

      if (eventHandler != null) {
        changes.removeConnectionStatusChanges(eventHandler);
      }

      try {
        if (startPullingTask != null) {
          startPullingTask.join();
        }
      } catch (OperationCancelledException e) {
        //ignore
      } catch (InterruptedException e) {
        // ignore
      }
      if (!connectionClosed) {
        closeSubscription();
      }
    }

    private void closeSubscription() {
      try (HttpJsonRequest closeRequest = createCloseRequest()) {
        closeRequest.executeRequest();
        connectionClosed = true;
      }
    }

    public Thread getStartPullingTask() {
      return startPullingTask;
    }

    @Override
    public IObservable<T> where(Predicate<T> predicate) {
      throw new IllegalStateException("Where is not supported in subscriptions!");
    }

  public static class DocumentProcessedEventArgs extends EventArgs {
    public DocumentProcessedEventArgs(int documentsProcessed) {
      this.documentsProcessed = documentsProcessed;
    }

    private final int documentsProcessed;

    public int getDocumentsProcessed() {
      return documentsProcessed;
    }
  }

  public static class LastProcessedEtagEventArgs extends EventArgs {
    private final Etag lastProcessedEtag;

    public LastProcessedEtagEventArgs(Etag lastProcessedEtag) {
      this.lastProcessedEtag = lastProcessedEtag;
    }

    public Etag getLastProcessedEtag() {
      return lastProcessedEtag;
    }
  }

}
