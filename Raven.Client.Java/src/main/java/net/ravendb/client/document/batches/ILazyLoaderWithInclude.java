package net.ravendb.client.document.batches;

import java.util.Collection;
import java.util.UUID;

import net.ravendb.abstractions.basic.Lazy;

import com.mysema.query.types.Path;

/**
 * Fluent interface for specifying include paths
 * for loading documents lazily
 * NOTE: Java version does not contain method load that skips class parameter - since we can't track type in method signature based on Path object
 */
public interface ILazyLoaderWithInclude {

  /**
   * Begin a load while including the specified path
   * @param path Path in documents in which server should look for a 'referenced' documents.
   */
  public ILazyLoaderWithInclude include(String path);

  /**
   * Begin a load while including the specified path
   * @param path Path in documents in which server should look for a 'referenced' documents.
   */
  public ILazyLoaderWithInclude include(Path<?> path);

  /**
   * Loads the specified entities with the specified ids.
   * @param clazz
   * @param ids Enumerable of Ids that should be loaded
   */
  public <TResult> Lazy<TResult[]> load(Class<TResult> clazz, String... ids);

  /**
   * Loads the specified entities with the specified ids.
   * @param ids Collection of Ids that should be loaded
   */
  public <TResult> Lazy<TResult[]> load(Class<TResult> clazz, Collection<String> ids);

  /**
   * Loads the specified entity with the specified id.
   * @param clazz
   * @param id Identifier of a entity that will be loaded.
   */
  public <TResult> Lazy<TResult> load(Class<TResult> clazz, String id);

  /**
   * Loads the specified entity with the specified id after applying
   * conventions on the provided id to get the real document id.
   *
   * This method allows you to call:
   * lazyLoad(Post.class, 1)
   * And that call will internally be translated to
   * lazyLoad(Post.class, "posts/1")
   * @param clazz
   * @param id
   */
  public <TResult> Lazy<TResult> load(Class<TResult> clazz, Number id);

  /**
   * Loads the specified entity with the specified id after applying
   * conventions on the provided id to get the real document id.
   *
   * This method allows you to call:
   * lazyLoad(Post.class, 1)
   * And that call will internally be translated to
   * lazyLoad(Post.class, "posts/1")
   * @param clazz
   * @param id
   */
  public <TResult> Lazy<TResult> load(Class<TResult> clazz, UUID id);

  /**
   * Loads the specified entities with the specified id after applying
   * conventions on the provided id to get the real document id.
   *
   * This method allows you to call:
   * lazyLoad(Post.class, 1,2,3);
   * And that call will internally be translated to
   * lazyLoad(Post.class, "posts/1", "posts/2", "posts/3")
   *
   * Or whatever your conventions specify.
   */
  public <TResult> Lazy<TResult[]> load(Class<TResult> clazz, Number... ids);

  /**
   * Loads the specified entities with the specified id after applying
   * conventions on the provided id to get the real document id.
   *
   * This method allows you to call:
   * lazyLoad(Post.class, 1,2,3);
   * And that call will internally be translated to
   * lazyLoad(Post.class, "posts/1", "posts/2", "posts/3")
   *
   * Or whatever your conventions specify.
   */
  public <TResult> Lazy<TResult[]> load(Class<TResult> clazz, UUID... ids);

}
