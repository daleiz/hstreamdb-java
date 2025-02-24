package io.hstream;

/** user can define actions when consume data from {@link Publisher} */
public interface Observer<V> {

  /**
   * Receives a value.
   *
   * @param value the value received
   */
  void onNext(V value);

  /**
   * Receives a terminating error.
   *
   * @param t the error occurred
   */
  void onError(Throwable t);

  /** Receives a notification of successful completion. */
  void onCompleted();
}
