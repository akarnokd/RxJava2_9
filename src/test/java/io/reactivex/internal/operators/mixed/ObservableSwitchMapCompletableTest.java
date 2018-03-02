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

import org.junit.Test;

import io.reactivex.*;
import io.reactivex.disposables.Disposables;
import io.reactivex.exceptions.*;
import io.reactivex.functions.*;
import io.reactivex.internal.functions.Functions;
import io.reactivex.observers.TestObserver;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.subjects.*;

public class ObservableSwitchMapCompletableTest {

    @Test
    public void normal() {
        Observable.range(1, 10)
        .switchMapCompletable(new Function<Integer, CompletableSource>() {
            @Override
            public CompletableSource apply(Integer v) throws Exception {
                return Completable.complete();
            }
        })
        .test()
        .assertResult();
    }

    @Test
    public void mainError() {
        Observable.<Integer>error(new TestException())
        .switchMapCompletable(new Function<Integer, CompletableSource>() {
            @Override
            public CompletableSource apply(Integer v) throws Exception {
                return Completable.complete();
            }
        })
        .test()
        .assertFailure(TestException.class);
    }

    @Test
    public void innerError() {
        PublishSubject<Integer> pp = PublishSubject.create();
        CompletableSubject cs = CompletableSubject.create();

        TestObserver<Void> to = pp.switchMapCompletable(Functions.justFunction(cs))
        .test();

        assertTrue(pp.hasObservers());
        assertFalse(cs.hasObservers());

        pp.onNext(1);

        assertTrue(cs.hasObservers());

        to.assertEmpty();

        cs.onError(new TestException());

        to.assertFailure(TestException.class);

        assertFalse(pp.hasObservers());
        assertFalse(cs.hasObservers());
    }

    @Test
    public void switchOver() {
        final CompletableSubject[] css = {
                CompletableSubject.create(),
                CompletableSubject.create()
        };

        PublishSubject<Integer> pp = PublishSubject.create();

        TestObserver<Void> to = pp.switchMapCompletable(new Function<Integer, CompletableSource>() {
            @Override
            public CompletableSource apply(Integer v) throws Exception {
                return css[v];
            }
        })
        .test();

        to.assertEmpty();

        pp.onNext(0);

        assertTrue(css[0].hasObservers());

        pp.onNext(1);

        assertFalse(css[0].hasObservers());
        assertTrue(css[1].hasObservers());

        pp.onComplete();

        to.assertEmpty();

        assertTrue(css[1].hasObservers());

        css[1].onComplete();

        to.assertResult();
    }

    @Test
    public void dispose() {
        PublishSubject<Integer> pp = PublishSubject.create();
        CompletableSubject cs = CompletableSubject.create();

        TestObserver<Void> to = pp.switchMapCompletable(Functions.justFunction(cs))
        .test();

        pp.onNext(1);

        assertTrue(pp.hasObservers());
        assertTrue(cs.hasObservers());

        to.dispose();

        assertFalse(pp.hasObservers());
        assertFalse(cs.hasObservers());
    }

    @Test
    public void checkDisposed() {
        PublishSubject<Integer> pp = PublishSubject.create();
        CompletableSubject cs = CompletableSubject.create();

        TestHelper.checkDisposed(pp.switchMapCompletable(Functions.justFunction(cs)));
    }

    @Test
    public void checkBadSource() {
        TestHelper.checkDoubleOnSubscribeObservableToCompletable(new Function<Observable<Object>, Completable>() {
            @Override
            public Completable apply(Observable<Object> f) throws Exception {
                return f.switchMapCompletable(Functions.justFunction(Completable.never()));
            }
        });
    }

    @Test
    public void mapperCrash() {
        Observable.range(1, 5).switchMapCompletable(new Function<Integer, CompletableSource>() {
            @Override
            public CompletableSource apply(Integer f) throws Exception {
                throw new TestException();
            }
        })
        .test()
        .assertFailure(TestException.class);
    }

    @Test
    public void mapperCancels() {
        final TestObserver<Void> to = new TestObserver<Void>();

        Observable.range(1, 5).switchMapCompletable(new Function<Integer, CompletableSource>() {
            @Override
            public CompletableSource apply(Integer f) throws Exception {
                to.cancel();
                return Completable.complete();
            }
        })
        .subscribe(to);

        to.assertEmpty();
    }

    @Test
    public void onNextInnerCompleteRace() {
        for (int i = 0; i < TestHelper.RACE_LONG_LOOPS; i++) {
            final PublishSubject<Integer> pp = PublishSubject.create();
            final CompletableSubject cs = CompletableSubject.create();

            TestObserver<Void> to = pp.switchMapCompletable(Functions.justFunction(cs)).test();

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
                }
            };

            TestHelper.race(r1, r2);

            to.assertEmpty();
        }
    }

    @Test
    public void onNextInnerErrorRace() {
        final TestException ex = new TestException();
        for (int i = 0; i < TestHelper.RACE_LONG_LOOPS; i++) {
            List<Throwable> errors = TestHelper.trackPluginErrors();
            try {
                final PublishSubject<Integer> pp = PublishSubject.create();
                final CompletableSubject cs = CompletableSubject.create();

                TestObserver<Void> to = pp.switchMapCompletable(Functions.justFunction(cs)).test();

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
                        cs.onError(ex);
                    }
                };

                TestHelper.race(r1, r2);

                to.assertError(new Predicate<Throwable>() {
                    @Override
                    public boolean test(Throwable e) throws Exception {
                        return e instanceof TestException || e instanceof CompositeException;
                    }
                });

                if (!errors.isEmpty()) {
                    TestHelper.assertUndeliverable(errors, 0, TestException.class);
                }
            } finally {
                RxJavaPlugins.reset();
            }
        }
    }

    @Test
    public void onErrorInnerErrorRace() {
        final TestException ex0 = new TestException();
        final TestException ex = new TestException();
        for (int i = 0; i < TestHelper.RACE_LONG_LOOPS; i++) {
            List<Throwable> errors = TestHelper.trackPluginErrors();
            try {
                final PublishSubject<Integer> pp = PublishSubject.create();
                final CompletableSubject cs = CompletableSubject.create();

                TestObserver<Void> to = pp.switchMapCompletable(Functions.justFunction(cs)).test();

                pp.onNext(1);

                Runnable r1 = new Runnable() {
                    @Override
                    public void run() {
                        pp.onError(ex0);
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
                });

                if (!errors.isEmpty()) {
                    TestHelper.assertUndeliverable(errors, 0, TestException.class);
                }
            } finally {
                RxJavaPlugins.reset();
            }
        }
    }

    @Test
    public void innerErrorThenMainError() {
        List<Throwable> errors = TestHelper.trackPluginErrors();
        try {
            new Observable<Integer>() {
                @Override
                protected void subscribeActual(Observer<? super Integer> s) {
                    s.onSubscribe(Disposables.empty());
                    s.onNext(1);
                    s.onError(new TestException("main"));
                }
            }
            .switchMapCompletable(Functions.justFunction(Completable.error(new TestException("inner"))))
            .test()
            .assertFailureAndMessage(TestException.class, "inner");

            TestHelper.assertUndeliverable(errors, 0, TestException.class, "main");
        } finally {
            RxJavaPlugins.reset();
        }
    }

    @Test
    public void innerErrorDelayed() {
        final PublishSubject<Integer> pp = PublishSubject.create();
        final CompletableSubject cs = CompletableSubject.create();

        TestObserver<Void> to = pp.switchMapCompletableDelayError(Functions.justFunction(cs)).test();

        pp.onNext(1);

        cs.onError(new TestException());

        to.assertEmpty();

        assertTrue(pp.hasObservers());

        pp.onComplete();

        to.assertFailure(TestException.class);
    }

    @Test
    public void mainCompletesinnerErrorDelayed() {
        final PublishSubject<Integer> pp = PublishSubject.create();
        final CompletableSubject cs = CompletableSubject.create();

        TestObserver<Void> to = pp.switchMapCompletableDelayError(Functions.justFunction(cs)).test();

        pp.onNext(1);
        pp.onComplete();

        to.assertEmpty();

        cs.onError(new TestException());

        to.assertFailure(TestException.class);
    }

    @Test
    public void mainErrorDelayed() {
        final PublishSubject<Integer> pp = PublishSubject.create();
        final CompletableSubject cs = CompletableSubject.create();

        TestObserver<Void> to = pp.switchMapCompletableDelayError(Functions.justFunction(cs)).test();

        pp.onNext(1);

        pp.onError(new TestException());

        to.assertEmpty();

        assertTrue(cs.hasObservers());

        cs.onComplete();

        to.assertFailure(TestException.class);
    }
}
