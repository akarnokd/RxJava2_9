package io.reactivex;

public class ObjNullCheck {
    static class A {
        class B {

        }

        B createB() {
            return new B();
        }
    }

    public static void main(String[] args) {
        A a = new A();

        A.B b = a.createB();

        System.out.println(b);
    }
}
