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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import io.reactivex.*;
import io.reactivex.exceptions.*;
import io.reactivex.functions.*;
import io.reactivex.internal.functions.Functions;
import io.reactivex.observers.TestObserver;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.subjects.*;

public class ObservableConcatMapCompletableTest {

    @Test
    public void simple() {
        Observable.range(1, 5)
        .concatMapCompletable(Functions.justFunction(Completable.complete()))
        .test()
        .assertResult();
    }

    @Test
    public void simple2() {
        final AtomicInteger counter = new AtomicInteger();
        Observable.range(1, 5)
        .concatMapCompletable(Functions.justFunction(Completable.fromAction(new Action() {
            @Override
            public void run() throws Exception {
                counter.incrementAndGet();
            }
        })))
        .test()
        .assertResult();

        assertEquals(5, counter.get());
    }

    @Test
    public void simpleLongPrefetch() {
        Observable.range(1, 1024)
        .concatMapCompletable(Functions.justFunction(Completable.complete()), 32)
        .test()
        .assertResult();
    }

    @Test
    public void mainError() {
        Observable.<Integer>error(new TestException())
        .concatMapCompletable(Functions.justFunction(Completable.complete()))
        .test()
        .assertFailure(TestException.class);
    }

    @Test
    public void innerError() {
        Observable.just(1)
        .concatMapCompletable(Functions.justFunction(Completable.error(new TestException())))
        .test()
        .assertFailure(TestException.class);
    }

    @Test
    public void innerErrorDelayed() {
        Observable.range(1, 5)
        .concatMapCompletableDelayError(
                new Function<Integer, CompletableSource>() {
                    @Override
                    public CompletableSource apply(Integer v) throws Exception {
                        return Completable.error(new TestException());
                    }
                }
        )
        .test()
        .assertFailure(CompositeException.class)
        .assertOf(new Consumer<TestObserver<Void>>() {
            @Override
            public void accept(TestObserver<Void> to) throws Exception {
                assertEquals(5, ((CompositeException)to.errors().get(0)).getExceptions().size());
            }
        });
    }

    @Test
    public void mapperCrash() {
        Observable.just(1)
        .concatMapCompletable(new Function<Integer, CompletableSource>() {
            @Override
            public CompletableSource apply(Integer v) throws Exception {
                throw new TestException();
            }
        })
        .test()
        .assertFailure(TestException.class);
    }

    @Test
    public void immediateError() {
        PublishSubject<Integer> pp = PublishSubject.create();
        CompletableSubject cs = CompletableSubject.create();

        TestObserver<Void> to = pp.concatMapCompletable(
                Functions.justFunction(cs)).test();

        to.assertEmpty();

        assertTrue(pp.hasObservers());
        assertFalse(cs.hasObservers());

        pp.onNext(1);

        assertTrue(cs.hasObservers());

        pp.onError(new TestException());

        assertFalse(cs.hasObservers());

        to.assertFailure(TestException.class);
    }

    @Test
    public void immediateError2() {
        PublishSubject<Integer> pp = PublishSubject.create();
        CompletableSubject cs = CompletableSubject.create();

        TestObserver<Void> to = pp.concatMapCompletable(
                Functions.justFunction(cs)).test();

        to.assertEmpty();

        assertTrue(pp.hasObservers());
        assertFalse(cs.hasObservers());

        pp.onNext(1);

        assertTrue(cs.hasObservers());

        cs.onError(new TestException());

        assertFalse(pp.hasObservers());

        to.assertFailure(TestException.class);
    }

    @Test
    public void boundaryError() {
        PublishSubject<Integer> pp = PublishSubject.create();
        CompletableSubject cs = CompletableSubject.create();

        TestObserver<Void> to = pp.concatMapCompletableDelayError(
                Functions.justFunction(cs), false).test();

        to.assertEmpty();

        assertTrue(pp.hasObservers());
        assertFalse(cs.hasObservers());

        pp.onNext(1);

        assertTrue(cs.hasObservers());

        pp.onError(new TestException());

        assertTrue(cs.hasObservers());

        to.assertEmpty();

        cs.onComplete();

        to.assertFailure(TestException.class);
    }

    @Test
    public void endError() {
        PublishSubject<Integer> pp = PublishSubject.create();
        final CompletableSubject cs = CompletableSubject.create();
        final CompletableSubject cs2 = CompletableSubject.create();

        TestObserver<Void> to = pp.concatMapCompletableDelayError(
                new Function<Integer, CompletableSource>() {
                    @Override
                    public CompletableSource apply(Integer v) throws Exception {
                        if (v == 1) {
                            return cs;
                        }
                        return cs2;
                    }
                }, true, 32
        )
        .test();

        to.assertEmpty();

        assertTrue(pp.hasObservers());
        assertFalse(cs.hasObservers());

        pp.onNext(1);

        assertTrue(cs.hasObservers());

        cs.onError(new TestException());

        assertTrue(pp.hasObservers());

        pp.onNext(2);

        to.assertEmpty();

        cs2.onComplete();

        assertTrue(pp.hasObservers());

        to.assertEmpty();

        pp.onComplete();

        to.assertFailure(TestException.class);
    }

    @Test
    public void doubleOnSubscribe() {
        TestHelper.checkDoubleOnSubscribeObservableToCompletable(
                new Function<Observable<Object>, Completable>() {
                    @Override
                    public Completable apply(Observable<Object> f)
                            throws Exception {
                        return f.concatMapCompletable(
                                Functions.justFunction(Completable.complete()));
                    }
                }
        );
    }

    @Test
    public void disposed() {
        TestHelper.checkDisposed(
                Observable.never()
                .concatMapCompletable(
                        Functions.justFunction(Completable.complete()))
        );
    }

    @Test
    public void immediateOuterInnerErrorRace() {
        final TestException ex = new TestException();
        for (int i = 0; i < TestHelper.RACE_LONG_LOOPS; i++) {
            List<Throwable> errors = TestHelper.trackPluginErrors();
            try {
                final PublishSubject<Integer> pp = PublishSubject.create();
                final CompletableSubject cs = CompletableSubject.create();

                TestObserver<Void> to = pp.concatMapCompletable(
                        Functions.justFunction(cs)
                )
                .test();

                pp.onNext(1);

                Runnable r1 = new Runnable() {
                    @Override
                    public void run() {
                        pp.onError(ex);
                    }
                };

                Runnable r2 = new Runnable() {
                    @Override
                    public void run() {
                        cs.onError(ex);
                    }
                };

                TestHelper.race(r1, r2);

                to.assertError(new Predicate<Throwable>() {
                    @Override
                    public boolean test(Throwable e) throws Exception {
                        return e instanceof TestException || e instanceof CompositeException;
                    }
                })
                .assertNotComplete();

                if (!errors.isEmpty()) {
                    TestHelper.assertUndeliverable(errors, 0, TestException.class);
                }
            } finally {
                RxJavaPlugins.reset();
            }
        }
    }

    @Test
    public void disposeInDrainLoop() {
        for (int i = 0; i < TestHelper.RACE_LONG_LOOPS; i++) {
            final PublishSubject<Integer> pp = PublishSubject.create();
            final CompletableSubject cs = CompletableSubject.create();

            final TestObserver<Void> to = pp.concatMapCompletable(
                    Functions.justFunction(cs)
            )
            .test();

            pp.onNext(1);

            Runnable r1 = new Runnable() {
                @Override
                public void run() {
                    pp.onNext(2);
                }
            };

            Runnable r2 = new Runnable() {
                @Override
                public void run() {
                    cs.onComplete();
                    to.cancel();
                }
            };

            TestHelper.race(r1, r2);

            to.assertEmpty();
        }
    }

    @Test
    public void doneButNotEmpty() {
        final PublishSubject<Integer> pp = PublishSubject.create();
        final CompletableSubject cs = CompletableSubject.create();

        final TestObserver<Void> to = pp.concatMapCompletable(
                Functions.justFunction(cs)
        )
        .test();

        pp.onNext(1);
        pp.onNext(2);
        pp.onComplete();

        cs.onComplete();

        to.assertResult();
    }
}
