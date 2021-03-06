## StreamEx changes the package name

Since version 0.5.0 the package name is changed from `javax.util.streamex` to `one.util.streamex`. 
The OSGi bundle name and Maven groupId are changed correspondingly. To migrate to StreamEx 0.5.0 you should:

* Replace every occurrence of `javax.util.streamex` to `one.util.streamex` in your Java files and OSGi manifests.
* Replace `io.github.amaembo` groupID with `one.util` in your build files (pom.xml, ivy.xml, build.gradle, etc.)
 
This change is discussed [here](https://github.com/amaembo/streamex/issues/8). Sorry for possible inconvenience.

# StreamEx 0.5.0
Enhancing Java 8 Streams.

This library defines four classes: `StreamEx`, `IntStreamEx`, `LongStreamEx`, `DoubleStreamEx`
which are fully compatible with Java 8 stream classes and provide many additional useful methods.
Also `EntryStream` class is provided which represents the stream of map entries and provides
additional functionality for this case. Finally there are some new useful collectors defined in `MoreCollectors`
class as well as primitive collectors concept.

Full API documentation is available [here](http://amaembo.github.io/streamex/javadoc/).

Take a look at the [Cheatsheet](CHEATSHEET.md) for brief introduction to the StreamEx!

StreamEx library main points are following:

* Shorter and convenient ways to do the common tasks.
* Better interoperability with older code.
* 100% compatibility with original JDK streams.
* Friendliness for parallel processing: any new feature takes the advantage on parallel streams as much as possible.
* Performance and minimal overhead. If StreamEx allows to solve the task using less code compared to standard Stream, it
should not be significantly slower than the standard way (and sometimes it's even faster).

[![Build Status](https://travis-ci.org/amaembo/streamex.png?branch=master)](https://travis-ci.org/amaembo/streamex)
[![Coverage Status](https://coveralls.io/repos/amaembo/streamex/badge.png?branch=master&service=github)](https://coveralls.io/github/amaembo/streamex?branch=master)

### Examples

Collector shortcut methods (toList, toSet, groupingBy, joining, etc.)
```java
List<String> userNames = StreamEx.of(users).map(User::getName).toList();
Map<Role, List<User>> role2users = StreamEx.of(users).groupingBy(User::getRole);
StreamEx.of(1,2,3).joining("; "); // "1; 2; 3"
```

Selecting stream elements of specific type
```java
public List<Element> elementsOf(NodeList nodeList) {
    return IntStreamEx.range(nodeList.getLength())
      .mapToObj(nodeList::item).select(Element.class).toList();
}
```

Adding elements to stream
```java
public List<String> getDropDownOptions() {
    return StreamEx.of(users).map(User::getName).prepend("(none)").toList();
}

public int[] addValue(int[] arr, int value) {
    return IntStreamEx.of(arr).append(value).toArray();
}
```

Removing unwanted elements and using the stream as Iterable:
```java
public void copyNonEmptyLines(Reader reader, Writer writer) throws IOException {
    for(String line : StreamEx.ofLines(reader).remove(String::isEmpty)) {
        writer.write(line);
        writer.write(System.lineSeparator());
    }
}
```

Selecting map keys by value predicate:
```java
Map<String, Role> nameToRole;

public Set<String> getEnabledRoleNames() {
    return StreamEx.ofKeys(nameToRole, Role::isEnabled).toSet();
}
```

Operating on key-value pairs:
```java
public Map<String, List<String>> invert(Map<String, List<String>> map) {
    return EntryStream.of(map).flatMapValues(List::stream).invert().grouping();
}

public Map<String, String> stringMap(Map<Object, Object> map) {
    return EntryStream.of(map).mapKeys(String::valueOf)
        .mapValues(String::valueOf).toMap();
}

Map<String, Group> nameToGroup;

public Map<String, List<User>> getGroupMembers(Collection<String> groupNames) {
    return StreamEx.of(groupNames).mapToEntry(nameToGroup::get)
        .nonNullValues().mapValues(Group::getMembers).toMap();
}
```

Pairwise differences:
```java
DoubleStreamEx.of(input).pairMap((a, b) -> b-a).toArray();
```

Support of byte/char/short/float types:
```java
short[] multiply(short[] src, short multiplier) {
    return IntStreamEx.of(src).map(x -> x*multiplier).toShortArray(); 
}
```

And more!

### License

This project is licensed under [Apache License, version 2.0](https://www.apache.org/licenses/LICENSE-2.0)

### Installation

Releases are available in [Maven Central](https://repo1.maven.org/maven2/one/util/streamex/)

To use from maven add this snippet to the pom.xml `dependencies` section:

```xml
<dependency>
  <groupId>one.util</groupId>
  <artifactId>streamex</artifactId>
  <version>0.5.0</version>
</dependency>
```

Pull requests are welcome.
