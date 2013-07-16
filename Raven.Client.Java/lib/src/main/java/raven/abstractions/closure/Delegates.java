package raven.abstractions.closure;

public class Delegates {
  public static class Delegate1<X> implements Action1<X> {
    @Override
    public void apply(X first) {
      // do nothing
    }
  }

  public static class Delegate2<X, Y> implements Action2<X, Y> {
    @Override
    public void apply(X first, Y second) {
      // do nothing
    }
  }

  public static class Delegate3<X, Y, Z> implements Action3<X, Y, Z> {
    @Override
    public void apply(X first, Y second, Z third) {
      // do nothing
    }
  }

  public static <X> Action1<X> delegate1() {
    return new Delegate1<>();
  }

  public static <X, Y> Action2<X, Y> delegate2() {
    return new Delegate2<>();
  }

  public static <X, Y, Z> Action3<X, Y, Z> delegate3() {
    return new Delegate3<>();
  }

}
