//
//  Manager.swift
//  BlueskyVideo
//
//  Created by Hailey on 9/10/24.
//

class Manager<T: AnyObject> {
  private let objects = NSHashTable<T>.weakObjects()

  func add(_ object: T) {
    objects.add(object)
  }

  func remove(_ object: T) {
    objects.remove(object)
  }

  func count() -> Int {
    return objects.count
  }

  func has(_ object: T) -> Bool {
    return objects.contains(object)
  }

  func getEnumerator() -> NSEnumerator? {
    return objects.objectEnumerator()
  }
}
