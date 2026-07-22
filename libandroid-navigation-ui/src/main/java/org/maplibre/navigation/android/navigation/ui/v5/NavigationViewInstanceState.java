package org.maplibre.navigation.android.navigation.ui.v5;

import android.os.Parcel;
import android.os.Parcelable;

class NavigationViewInstanceState implements Parcelable {

  private boolean instructionViewVisible;

  NavigationViewInstanceState(boolean instructionViewVisible) {
    this.instructionViewVisible = instructionViewVisible;
  }

  boolean isInstructionViewVisible() {
    return instructionViewVisible;
  }


  private NavigationViewInstanceState(Parcel in) {
    instructionViewVisible = in.readByte() != 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeByte((byte) (instructionViewVisible ? 1 : 0));
  }

  @Override
  public int describeContents() {
    return 0;
  }

  public static final Creator<NavigationViewInstanceState> CREATOR = new Creator<NavigationViewInstanceState>() {
    @Override
    public NavigationViewInstanceState createFromParcel(Parcel in) {
      return new NavigationViewInstanceState(in);
    }

    @Override
    public NavigationViewInstanceState[] newArray(int size) {
      return new NavigationViewInstanceState[size];
    }
  };
}
