class MyClass() {
    var test = ""
        [deprecated("")] set
}

// TESTED_OBJECT_KIND: function
// TESTED_OBJECTS: MyClass, setTest
// FLAGS: ACC_DEPRECATED, ACC_PUBLIC, ACC_FINAL
