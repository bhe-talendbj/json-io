package com.cedarsoftware.util.io

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br>
 *         Copyright (c) Cedar Software LLC
 *         <br><br>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br><br>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br><br>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
class TestRefs
{
    private static class TestReferences implements Serializable
    {
        // Field ordering below is vital (b, a, c).  We treat JSON keys in alphabetical
        // order, whereas Java Field walking is in declaration order.
        private TestObject _b
        private TestObject[] _a
        private TestObject[] _c

        private TestObject[][] _foo

        private TestObject _back_a
        private TestObject _back_b
        private TestObject _back_c

        private TestObject _cycle_a
        private TestObject _cycle_b

        private TestObject _polymorphic
        private TestObject[] _polymorphics

        private char _big;

        private void init()
        {
            _big = '\ufbfc'
            _b = new TestObject('B')
            _a = [_b] as TestObject[]
            _c = [_b] as TestObject[]
            _foo = [null, [], [new TestObject('alpha'), new TestObject('beta')] as TestObject[]] as TestObject[][]
            _back_a = new TestObject('back test')
            _back_b = _back_a;
            _back_c = _back_b;

            _cycle_a = new TestObject('a')
            _cycle_b = new TestObject('b')
            _cycle_a._other = _cycle_b;
            _cycle_b._other = _cycle_a;

            _polymorphic = new TestObjectKid('dilbert', 'dilbert@myotherdrive.com')
            _polymorphics = [new TestObjectKid('dog', 'dog@house.com'), new TestObjectKid('cat', 'cat@house.com'), new TestObject('shortie')] as TestObject[]
        }
    }

    static class Column
    {
        final Object value

        Column(Object v)
        {
            value = v
        }
    }

    static class Axis
    {
        final String name
        final Column column

        Axis(String n, Column c)
        {
            name = n
            column = c
        }
    }

    static class Delta
    {
        final Object newValue

        Delta(Object n)
        {
            newValue = n
        }
    }

    @Test
    void testReferences()
    {
        TestReferences obj = new TestReferences()
        obj.init()
        String jsonOut = TestUtil.getJsonString(obj)
        TestUtil.printLine(jsonOut)

        TestReferences root = (TestReferences) TestUtil.readJsonObject(jsonOut)

        assertTrue(root._a.length == 1)
        assertTrue(root._b != null)
        assertTrue(root._a[0] == root._b)
        assertTrue(root._c.length == 1)
        assertTrue(root._c[0] == root._b)

        assertTrue(root._foo.length == 3)
        assertNull(root._foo[0])
        assertTrue(root._foo[1].length == 0)
        assertTrue(root._foo[2].length == 2)

        assertTrue(root._back_b == root._back_a)
        assertTrue(root._back_c == root._back_a)

        assertTrue(root._cycle_a._name.equals('a'))
        assertTrue(root._cycle_b._name.equals('b'))
        assertTrue(root._cycle_a._other == root._cycle_b)
        assertTrue(root._cycle_b._other == root._cycle_a)

        assertTrue(root._polymorphic.class.equals(TestObjectKid.class))
        TestObjectKid kid = (TestObjectKid) root._polymorphic
        assert 'dilbert' == kid._name
        assert 'dilbert@myotherdrive.com' == kid._email

        assertTrue(root._polymorphics.length == 3)
        TestObjectKid kid1 = (TestObjectKid) root._polymorphics[0]
        TestObjectKid kid2 = (TestObjectKid) root._polymorphics[1]
        TestObject kid3 = root._polymorphics[2]
        assertSame(kid1.class, TestObjectKid.class)
        assertSame(kid1.class, kid2.class)
        assertSame(kid3.class, TestObject.class)
        assert 'dog' == kid1._name
        assert 'dog@house.com' == kid1._email
        assert 'cat' == kid2._name
        assert 'cat@house.com' == kid2._email
        assert 'shortie' == kid3._name
        assert '\ufbfc' == root._big
    }

    @Test
    void testRefResolution()
    {
        TestObject a = new TestObject('a')
        TestObject b = new TestObject('b')
        a._other = b
        b._other = a
        String json = JsonWriter.objectToJson(a)

        TestObject aa = TestUtil.readJsonObject(json,[(JsonReader.CUSTOM_READER_MAP):[(TestObject.class):new JsonReader.JsonClassReaderEx() {
            Object read(Object jOb, Deque<JsonObject<String, Object>> stack, Map<String, Object> args)
            {
                JsonObject jObj = (JsonObject) jOb
                TestObject x = new TestObject(jObj.name)
                JsonObject b1 = jObj._other
                JsonObject aRef = b1._other
                assert aRef.isReference()
                JsonReader reader = JsonReader.JsonClassReaderEx.Support.getReader(args)
                JsonObject aTarget = reader.getRefTarget(aRef)
                assert aRef != aTarget
                assert aTarget._name == 'a'
                return x
            }}]])
    }

    @Test
    void testTypedAndUntypedReference()
    {
        Column column = new Column('foo')
        Axis axis = new Axis('state', column)
        Delta delta1 = new Delta(column)
        Delta delta2 = new Delta(axis)

        List<Delta> deltas = []
        deltas.add(delta2)
        deltas.add(delta1)

        // With forward reference
        String json = """\
{"@type":"java.util.ArrayList","@items":[{"@type":"com.cedarsoftware.util.io.TestRefs\$Delta","newValue":{"@ref":1}}, {"@type":"com.cedarsoftware.util.io.TestRefs\$Delta","newValue":{"@type":"com.cedarsoftware.util.io.TestRefs\$Axis","name":"state","column":{"@id":1,"value":"foo"}}}]}"""
        List<Object> newList = JsonReader.jsonToJava(json) as List
        Delta d1 = newList[0] as Delta
        Delta d2 = newList[1] as Delta

        assert d1.newValue instanceof Column
        assert d2.newValue instanceof Axis
        assert ((d2.newValue) as Axis).column instanceof Column

        // Backward reference
        json = """\
{"@type":"java.util.ArrayList","@items":[{"@type":"com.cedarsoftware.util.io.TestRefs\$Delta","newValue":{"@type":"com.cedarsoftware.util.io.TestRefs\$Axis","name":"state","column":{"@id":1,"value":"foo"}}},{"@type":"com.cedarsoftware.util.io.TestRefs\$Delta","newValue":{"@ref":1}}]}"""
        newList = JsonReader.jsonToJava(json) as List
        d1 = newList[0] as Delta
        d2 = newList[1] as Delta

        assert d1.newValue instanceof Axis
        assert ((d1.newValue) as Axis).column instanceof Column
        assert d2.newValue instanceof Column
    }
}
