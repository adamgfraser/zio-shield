package example

object Example {
  def defBodyNullable(foo: String): String = {
    if (foo.length > 1) {
      foo
    } else {
      null
    }
  }

  val foo = defBodyNullable("foo")
}
