/**
 * Copyright (c) 2016-present, RxJava Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.internal.operators.mixed;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import io.reactivex.*;
import io.reactivex.disposables.Disposables;
import io.reactivex.exceptions.*;
import io.reactivex.functions.*;
import io.reactivex.internal.functions.Functions;
import io.reactivex.observers.TestObserver;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.*;

public class ObservableConcatMapMaybeTest {

    @Test
    public void simple() {
        Observable.range(1, 5)
        .concatMapMaybe(new Function<Integer, MaybeSource<Integer>>() {
            @Override
            public MaybeSource<Integer> apply(Integer v)
                    throws Exception {
                return Maybe.just(v);
            }
        })
        .test()
        .assertResult(1, 2, 3, 4, 5);
    }

    @Test
    public void simpleLong() {
        Observable.range(1, 1024)
        .concatMapMaybe(new Function<Integer, MaybeSource<Integer>>() {
            @Override
            public MaybeSource<Integer> apply(Integer v)
                    throws Exception {
                return Maybe.just(v);
            }
        }, 32)
        .test()
        .assertValueCount(1024)
        .assertNoErrors()
        .assertComplete();
    }

    @Test
    public void empty() {
        Observable.range(1, 10)
        .concatMapMaybe(new Function<Integer, MaybeSource<Integer>>() {
            @Override
            public MaybeSource<Integer> apply(Integer v)
                    throws Exception {
                return Maybe.empty();
            }
        })
        .test()
        .assertResult();
    }

    @Test
    public void mixed() {
        Observable.range(1, 10)
        .concatMapMaybe(new Function<Integer, MaybeSource<Integer>>() {
            @Override
            public MaybeSource<Integer> apply(Integer v)
                    throws Exception {
                if (v % 2 == 0) {
                    return Maybe.just(v);
                }
                return Maybe.empty();
            }
        })
        .test()
        .assertResult(2, 4, 6, 8, 10);
    }

    @Test
    public void mixedLong() {
        Observable.range(1, 1024)
        .concatMapMaybe(new Function<Integer, MaybeSource<Integer>>() {
            @Override
            public MaybeSource<Integer> apply(Integer v)
                    throws Exception {
                if (v % 2 == 0) {
                    return Maybe.just(v).subscribeOn(Schedulers.computation());
                }
                return Maybe.<Integer>empty().subscribeOn(Schedulers.computation());
            }
        })
        .test()
        .awaitDone(5, TimeUnit.SECONDS)
        .assertValueCount(512)
        .assertNoErrors()
        .assertComplete()
        .assertOf(new Consumer<TestObserver<Integer>>() {
            @Override
            public void accept(TestObserver<Integer> ts) throws Exception {
                for (int i = 0; i < 512; i ++) {
                    ts.assertValueAt(i, (i + 1) * 2);
                }
            }
        });
    }

    @Test
    public void mainError() {
        Observable.error(new TestException())
        .concatMapMaybe(Functions.justFunction(Maybe.just(1)))
        .test()
        .assertFailure(TestException.class);
    }

    @Test
    public void innerError() {
        Observable.just(1)
        .concatMapMaybe(Functions.justFunction(Maybe.error(new TestException())))
        .test()
        .assertFailure(TestException.class);
    }

    @Test
    public void mainBoundaryErrorInnerSuccess() {
        PublishSubject<Integer> pp = PublishSubject.create();
        MaybeSubject<Integer> ms = MaybeSubject.create();

        TestObserver<Integer> ts = pp.concatMapMaybeDelayError(Functions.justFunction(ms), false).test();

        ts.assertEmpty();

        pp.onNext(1);

        assertTrue(ms.hasObservers());

        pp.onError(new TestException());

        assertTrue(ms.hasObservers());

        ts.assertEmpty();

        ms.onSuccess(1);

        ts.assertFailure(TestException.class, 1);
    }

    @Test
    public void mainBoundaryErrorInnerEmpty() {
        PublishSubject<Integer> pp = PublishSubject.create();
        MaybeSubject<Integer> ms = MaybeSubject.create();

        TestObserver<Integer> ts = pp.concatMapMaybeDelayError(Functions.justFunction(ms), false).test();

        ts.assertEmpty();

        pp.onNext(1);

        assertTrue(ms.hasObservers());

        pp.onError(new TestException());

        assertTrue(ms.hasObservers());

        ts.assertEmpty();

        ms.onComplete();

        ts.assertFailure(TestException.class);
    }

    @Test
    public void doubleOnSubscribe() {
        TestHelper.checkDoubleOnSubscribeObservable(
                new Function<Observable<Object>, Observable<Object>>() {
                    @Override
                    public Observable<Object> apply(Observable<Object> f)
                            throws Exception {
                        return f.concatMapMaybeDelayError(
                                Functions.justFunction(Maybe.empty()));
                    }
                }
        );
    }

    @Test
    public void take() {
        Observable.range(1, 5)
        .concatMapMaybe(new Function<Integer, MaybeSource<Integer>>() {
            @Override
            public MaybeSource<Integer> apply(Integer v)
                    throws Exception {
                return Maybe.just(v);
            }
        })
        .take(3)
        .test()
        .assertResult(1, 2, 3);
    }

    @Test
    public void cancel() {
        Observable.range(1, 5).concatWith(Observable.<Integer>never())
        .concatMapMaybe(new Function<Integer, MaybeSource<Integer>>() {
            @Override
            public MaybeSource<Integer> apply(Integer v)
                    throws Exception {
                return Maybe.just(v);
            }
        })
        .test()
        .assertValues(1, 2, 3, 4, 5)
        .assertNoErrors()
        .assertNotComplete()
        .cancel();
    }

    @Test
    public void mainErrorAfterInnerError() {
        List<Throwable> errors = TestHelper.trackPluginErrors();
        try {
            new Observable<Integer>() {
                @Override
                protected void subscribeActual(Observer<? super Integer> s) {
                    s.onSubscribe(Disposables.empty());
                    s.onNext(1);
                    s.onError(new TestException("outer"));
                }
            }
            .concatMapMaybe(
                    Functions.justFunction(Maybe.error(new TestException("inner"))), 1
            )
            .test()
            .assertFailureAndMessage(TestException.class, "inner");

            TestHelper.assertUndeliverable(errors, 0, TestException.class, "outer");
        } finally {
            RxJavaPlugins.reset();
        }
    }

    @Test
    public void innerErrorAfterMainError() {
        List<Throwable> errors = TestHelper.trackPluginErrors();
        try {
            final PublishSubject<Integer> pp = PublishSubject.create();

            final AtomicReference<MaybeObserver<? super Integer>> obs = new AtomicReference<MaybeObserver<? super Integer>>();

            TestObserver<Integer> ts = pp.concatMapMaybe(
                    new Function<Integer, MaybeSource<Integer>>() {
                        @Override
                        public MaybeSource<Integer> apply(Integer v)
                                throws Exception {
                            return new Maybe<Integer>() {
                                    @Override
                                    protected void subscribeActual(
                                            MaybeObserver<? super Integer> observer) {
                                        observer.onSubscribe(Disposables.empty());
                                        obs.set(observer);
                                    }
                            };
                        }
                    }
            ).test();

            pp.onNext(1);

            pp.onError(new TestException("outer"));
            obs.get().onError(new TestException("inner"));

            ts.assertFailureAndMessage(TestException.class, "outer");

            TestHelper.assertUndeliverable(errors, 0, TestException.class, "inner");
        } finally {
            RxJavaPlugins.reset();
        }
    }

    @Test
    public void delayAllErrors() {
        Observable.range(1, 5)
        .concatMapMaybeDelayError(new Function<Integer, MaybeSource<? extends Object>>() {
            @Override
            public MaybeSource<? extends Object> apply(Integer v)
                    throws Exception {
                return Maybe.error(new TestException());
            }
        })
        .test()
        .assertFailure(CompositeException.class)
        .assertOf(new Consumer<TestObserver<Object>>() {
            @Override
            public void accept(TestObserver<Object> ts) throws Exception {
                CompositeException ce = (CompositeException)ts.errors().get(0);
                assertEquals(5, ce.getExceptions().size());
            }
        });
    }

    @Test
    public void mapperCrash() {
        final PublishSubject<Integer> pp = PublishSubject.create();

        TestObserver<Object> ts = pp
        .concatMapMaybe(new Function<Integer, MaybeSource<? extends Object>>() {
            @Override
            public MaybeSource<? extends Object> apply(Integer v)
                    throws Exception {
                        throw new TestException();
                    }
        })
        .test();

        ts.assertEmpty();

        assertTrue(pp.hasObservers());

        pp.onNext(1);

        ts.assertFailure(TestException.class);

        assertFalse(pp.hasObservers());
    }

    @Test
    public void disposed() {
        TestHelper.checkDisposed(Observable.just(1)
                .concatMapMaybe(Functions.justFunction(Maybe.never()))
        );
    }
}
